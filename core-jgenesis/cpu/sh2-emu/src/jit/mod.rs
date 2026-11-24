//! SH-2 block engine: block discovery, block cache with chaining, and a threaded micro-op
//! executor. This is the reference backend of the SH-2 JIT (see `docs/SH2-JIT-DESIGN.md`); the
//! ARM64 code generator consumes the same `Block` description.
//!
//! A block is a straight-line run of cache-resident instructions (see `form`). Every instruction
//! becomes one micro-op:
//! * register-only instructions run as *native* micro-ops that touch nothing but the register
//!   file: no program counter bookkeeping, no fetch, no cycle charge, no stop check;
//! * branches are native micro-ops with targets resolved at formation time where they are static;
//! * everything else (memory, exceptions, `SLEEP`, ...) is a *call-out* to the interpreter's own
//!   handler with the exact interpreter state materialized around it.
//!
//! Consecutive native micro-ops form a *segment*. A segment's cycle cost is static (one cycle per
//! instruction plus the fixed extra charges of branches), so the interpreter's per-instruction
//! `should_stop_execution` check is replaced by one entry guard per segment: when the counter plus
//! the worst-case cost of all but the segment's last instruction stays below the limit, no stop
//! can occur inside the segment and its whole cost is charged at its end. Otherwise the segment
//! runs in checked mode with the interpreter's per-instruction stop check. Call-outs keep the
//! interpreter's exact order: pending cycles are charged before the handler runs, so the bus sees
//! the true counter at every access.
//!
//! Exactness rests on the same facts as the predecoded executor (`predecode`):
//! * blocks are only trusted while the cache lines holding their code are unchanged. The cache
//!   keeps a code-line bitmap (`CpuCache::mark_code_line`): a change to a line that holds block
//!   code bumps one global counter, so validation is a single compare (`Block::valid`), and only
//!   then are the block's own lines checked;
//! * instruction fetch hits have two side effects (LRU bits, host fetch memo) that are applied
//!   exactly when the interpreter would apply them (first instruction, line changes and after
//!   any call-out that may touch the cache);
//! * control flow after call-outs is verified against the recorded successor.
//!
//! All state here is host-only (`HostCache`): never serialized, emptied on clone.

// The QA `jit-runtime` build replaces `Sh2::execute` and never selects this engine
#![cfg_attr(feature = "jit-runtime", allow(dead_code, unused_imports))]

#[cfg(any(test, feature = "jit-arm64"))]
pub(crate) mod a64;
#[cfg(test)]
mod a64_sim;
#[cfg(any(test, feature = "jit-arm64"))]
pub(crate) mod a64_translate;
mod form;
#[cfg(feature = "jit-verify")]
mod verify;

use crate::bus::{AccessContext, BusInterface};
use crate::instructions::OpcodeFn;
use crate::{ExecutionBody, Sh2};
use std::fmt::{self, Debug, Formatter};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};

pub(crate) use form::Block;

static ENABLED: AtomicBool = AtomicBool::new(false);

/// Selects the SH-2 block engine for buses that support idle-loop skipping (real, non-debugger
/// buses). Output is bit-identical either way; this only changes host speed.
pub fn set_jit_enabled(enabled: bool) {
    ENABLED.store(enabled, Ordering::Relaxed);
}

#[inline(always)]
pub(crate) fn jit_enabled() -> bool {
    ENABLED.load(Ordering::Relaxed)
}

#[cfg(feature = "jit-verify")]
pub use verify::{set_jit_verify, set_jit_verify_deep_every};

pub(crate) const MAX_OPS: usize = 32;
pub(crate) const MAX_LINES: usize = 6;
const SLOTS: usize = 8192;

// Micro-op kinds
/// Register-only instruction, never leaves the block
pub(crate) const K_NATIVE: u8 = 0;
/// `BT`/`BF`: ends its segment, leaves the block when taken
pub(crate) const K_BRANCH: u8 = 1;
/// Delayed branch (its slot follows in the same segment and ends the block)
pub(crate) const K_DELAYED: u8 = 2;
/// Interpreter handler with materialized state
pub(crate) const K_CALLOUT: u8 = 3;

// Micro-op flags
/// Executes in the delay slot of the previous instruction
pub(crate) const F_SLOT: u8 = 1;
/// The interpreter's fetch of this instruction may not be satisfied by the fetch memo
pub(crate) const F_FETCH: u8 = 2;
/// Call-out that may touch the CPU cache (block must be revalidated after it)
pub(crate) const F_MEM: u8 = 4;
/// Taken branch is a backward branch the idle-loop detector must be told about
pub(crate) const F_NOTE: u8 = 8;

/// Link index used when the successor must be looked up in the table
const LINK_NONE: u8 = 2;

/// Native micro-op: returns whether the branch was taken / the alternative link applies
pub(crate) type NativeFn = fn(&mut Sh2, &Uop) -> bool;

#[derive(Clone, Copy, Debug)]
pub(crate) struct Uop {
    /// `NativeFn` or `OpcodeFn<Bus>` of the bus type the cache was built for
    pub(crate) f: usize,
    pub(crate) addr: u32,
    /// Static operand: branch target, return address, `MOVA` result, ...
    pub(crate) imm: u32,
    pub(crate) opcode: u16,
    /// Hot instruction dispatched with a direct call (`form::hot_code`), 0 = use `f`
    pub(crate) code: u8,
    pub(crate) kind: u8,
    pub(crate) flags: u8,
    /// Static cycle cost (1 plus fixed handler charges); extra charge when taken
    pub(crate) cost: u8,
    pub(crate) extra: u8,
    pub(crate) line: u8,
    /// Index one past the last micro-op of the segment this one belongs to
    pub(crate) seg_end: u8,
    /// Worst-case cost of the micro-ops from this one up to, excluding, its segment's last
    pub(crate) guard: u16,
}

pub(crate) const EMPTY_UOP: Uop = Uop {
    f: 0,
    addr: 0,
    imm: 0,
    opcode: 0,
    code: 0,
    kind: K_NATIVE,
    flags: 0,
    cost: 0,
    extra: 0,
    line: 0,
    seg_end: 0,
    guard: 0,
};

/// Direct link from a block exit to the block that followed it last time. A link is only
/// followed while the target slot still holds the same block (`version`), so re-forming or
/// replacing a block unlinks every exit pointing at it without bookkeeping.
#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct Link {
    pc: u32,
    slot: u16,
    version: u32,
}

// Interior-mutable validation stamp (relaxed atomic only to keep `Sh2` `Sync`)
#[derive(Default, Debug)]
pub(crate) struct Seen(AtomicU32);

impl Clone for Seen {
    fn clone(&self) -> Self {
        Self(AtomicU32::new(self.get()))
    }
}

impl Seen {
    #[inline(always)]
    pub(crate) fn get(&self) -> u32 {
        self.0.load(Ordering::Relaxed)
    }

    #[inline(always)]
    pub(crate) fn set(&self, value: u32) {
        self.0.store(value, Ordering::Relaxed);
    }
}

pub(crate) struct JitCache {
    table: Vec<Block>,
    // Address of the opcode table the call-out handlers were taken from
    table_id: usize,
}

impl Default for JitCache {
    fn default() -> Self {
        Self { table: Vec::new(), table_id: 0 }
    }
}

// Cloned CPUs (rewind/run-ahead snapshots, verifier shadows) start with no blocks
impl Clone for JitCache {
    fn clone(&self) -> Self {
        Self::default()
    }
}

impl Debug for JitCache {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        f.debug_struct("JitCache").finish_non_exhaustive()
    }
}

impl JitCache {
    #[inline(always)]
    fn ensure_table(&mut self, table_id: usize) {
        if self.table_id != table_id || self.table.is_empty() {
            self.table.clear();
            self.table.resize_with(SLOTS, Block::empty);
            self.table_id = table_id;
        }
    }
}

#[inline(always)]
fn slot_index(pc: u32) -> usize {
    (((pc >> 1) ^ (pc >> 12)) as usize) & (SLOTS - 1)
}

/// Host statistics of the block engine (difftest/benchmark builds only count them)
#[derive(Debug, Clone, Copy, Default)]
pub struct JitStats {
    pub blocks_formed: u64,
    /// Invalidated blocks whose code was found unchanged (lines re-located instead of re-forming)
    pub blocks_refreshed: u64,
    /// Formations that evicted a different block from the direct-mapped slot
    pub slot_conflicts: u64,
    pub block_runs: u64,
    pub chained_runs: u64,
    pub instructions: u64,
    pub native_instructions: u64,
    pub checked_segments: u64,
    pub fast_segments: u64,
    pub verified_slices: u64,
    pub verified_blocks: u64,
    pub deep_compares: u64,
    /// Block-engine entries refused because the pc is outside cached space (uncached code)
    pub uncached_entries: u64,
    /// Uncached entries by region (`pc >> 25 & 3`: boot ROM/registers, cartridge, frame buffer,
    /// SDRAM on the 32X) for `0x2xxxxxxx` program counters
    pub uncached_regions: [u64; 4],
    /// Block formations that failed because the first fetch would miss the cache
    pub form_misses: u64,
}

#[cfg(feature = "jit-verify")]
mod stats {
    use super::JitStats;
    use std::cell::Cell;

    thread_local! {
        pub(super) static STATS: Cell<JitStats> = Cell::new(JitStats::default());
    }

    #[inline(always)]
    pub(super) fn bump(f: impl FnOnce(&mut JitStats)) {
        STATS.with(|s| {
            let mut v = s.get();
            f(&mut v);
            s.set(v);
        });
    }
}

#[cfg(feature = "jit-verify")]
macro_rules! stat {
    ($s:ident => $($t:tt)*) => { $crate::jit::stats::bump(|$s| { $($t)* }) };
}

#[cfg(not(feature = "jit-verify"))]
macro_rules! stat {
    ($($t:tt)*) => {};
}

#[allow(unused_imports)]
pub(crate) use stat;

/// Statistics of the current thread (zero unless built with `jit-verify`)
#[must_use]
pub fn jit_stats() -> JitStats {
    #[cfg(feature = "jit-verify")]
    {
        stats::STATS.with(std::cell::Cell::get)
    }
    #[cfg(not(feature = "jit-verify"))]
    {
        JitStats::default()
    }
}

pub fn reset_jit_stats() {
    #[cfg(feature = "jit-verify")]
    stats::STATS.with(|s| s.set(JitStats::default()));
}

pub(crate) struct Jit;

impl<Bus: BusInterface> ExecutionBody<Bus> for Jit {
    const IDLE_LOOPS: bool = true;

    #[inline(always)]
    fn opcode(&mut self, _: &mut Sh2, _: &mut Bus, _: u16) -> bool {
        false
    }

    #[inline(always)]
    fn block(&mut self, cpu: &mut Sh2, bus: &mut Bus, ticks: u64) -> (u64, bool) {
        cpu.jit_run(bus, ticks)
    }
}

/// How a block run ended
#[derive(Clone, Copy)]
struct Exit {
    executed: usize,
    stopped: bool,
    link: u8,
}

impl Sh2 {
    #[inline]
    pub(crate) fn execute_jit<Bus: BusInterface>(&mut self, ticks: u64, bus: &mut Bus) {
        #[cfg(feature = "jit-verify")]
        if verify::verify_enabled() {
            return self.execute_verified(ticks, bus);
        }
        self.jit.ensure_table(std::ptr::from_ref(Bus::opcode_table()) as usize);
        self.execute_with_body(ticks, bus, &mut Jit);
    }

    #[cfg(test)]
    pub(crate) fn execute_jit_for_test<Bus: BusInterface>(&mut self, ticks: u64, bus: &mut Bus) {
        self.jit.ensure_table(std::ptr::from_ref(Bus::opcode_table()) as usize);
        self.execute_with_body(ticks, bus, &mut Jit);
    }

    /// Runs blocks starting at the current pc, following chained exits, for up to `ticks`
    /// instructions. Returns the number executed and whether `should_stop_execution` became
    /// true. Executing nothing is always allowed: the caller then single-steps the interpreter.
    #[inline]
    pub(crate) fn jit_run<Bus: BusInterface>(&mut self, bus: &mut Bus, ticks: u64) -> (u64, bool) {
        let mut total = 0u64;
        // Exit of the previous block in this call: (slot, version, link index)
        let mut prev: Option<(usize, u32, u8)> = None;

        loop {
            let pc = self.registers.pc;
            stat!(s => if pc >> 29 != 0 && pc >> 29 != 6 {
                s.uncached_entries += 1;
                if pc >> 29 == 1 {
                    s.uncached_regions[((pc >> 25) & 3) as usize] += 1;
                }
            });
            // A pending delay slot belongs to a branch the interpreter executed; the watched idle
            // loop head is handled by the caller's arrival logic
            if (pc >> 29 != 0 && pc >> 29 != 6)
                || pc & 1 != 0
                || self.registers.next_op_in_delay_slot
                || pc == self.idle_watch.0.0
                || total == ticks
            {
                break;
            }

            let slot = self.jit_lookup::<Bus>(pc, prev);
            let Some(slot) = slot else {
                stat!(s => s.form_misses += 1);
                break;
            };

            // SAFETY: the table is only modified by `jit_lookup`/`jit_link` in this function,
            // never by instruction handlers, bus callbacks or nested executions (those run other
            // `Sh2` values), and it is never resized after allocation. The block therefore stays
            // untouched while it executes.
            let block: &Block = unsafe { &*std::ptr::from_ref(&self.jit.table[slot]) };
            let version = block.version;

            let mut n = usize::from(block.n).min((ticks - total) as usize);
            // Arrival at the watched loop head is handled by the caller. The watch only changes in
            // taken branches, which end the block.
            let to_watch = self.idle_watch.0.0.wrapping_sub(pc);
            if to_watch & 1 == 0 && ((to_watch >> 1) as usize) < n {
                n = (to_watch >> 1) as usize;
            }
            if n == 0 {
                break;
            }

            stat!(s => s.block_runs += 1; if prev.is_some() { s.chained_runs += 1 });
            let exit = self.jit_exec(bus, block, n);
            total += exit.executed as u64;
            stat!(s => s.instructions += exit.executed as u64);

            #[cfg(feature = "jit-verify")]
            bus.jit_checkpoint(ticks - total, self);

            if exit.stopped {
                return (total, true);
            }
            if exit.executed == 0 {
                break;
            }
            prev = (exit.link != LINK_NONE).then_some((slot, version, exit.link));
        }

        (total, false)
    }

    /// Finds (or forms) a valid block for `pc`, following and updating the previous exit's link
    #[inline(always)]
    fn jit_lookup<Bus: BusInterface>(&mut self, pc: u32, prev: Option<(usize, u32, u8)>) -> Option<usize> {
        let mut slot = slot_index(pc);
        let mut linked = false;
        if let Some((from, from_version, link)) = prev {
            let l = self.jit.table[from].links[usize::from(link)];
            if l.pc == pc {
                let target = &self.jit.table[usize::from(l.slot)];
                if target.version == l.version && target.n != 0 && target.start == pc {
                    slot = usize::from(l.slot);
                    linked = true;
                }
            }
            let _ = from_version;
        }

        let cached = &mut self.jit.table[slot];
        let refreshed = cached.n != 0
            && cached.start == pc
            && !cached.valid(&self.cache)
            && cached.refresh(&self.cache);
        if refreshed {
            stat!(s => s.blocks_refreshed += 1);
            for line in &cached.lines[..usize::from(cached.nlines)] {
                self.cache.mark_code_line(line.way, line.entry);
            }
        }
        let cached = &self.jit.table[slot];
        if !(cached.n != 0 && cached.start == pc && cached.valid(&self.cache)) {
            stat!(s => if cached.n != 0 && cached.start != pc { s.slot_conflicts += 1 });
            let formed = self.jit_form::<Bus>(pc)?;
            stat!(s => s.blocks_formed += 1);
            for line in &formed.lines[..usize::from(formed.nlines)] {
                self.cache.mark_code_line(line.way, line.entry);
            }
            let version = self.jit.table[slot].version.wrapping_add(1);
            let entry = &mut self.jit.table[slot];
            *entry = formed;
            entry.version = version;
            entry.seen.set(self.cache.code_gen());
            linked = false;
        }

        if !linked && let Some((from, from_version, link)) = prev {
            let target_version = self.jit.table[slot].version;
            let source = &mut self.jit.table[from];
            // The previous block may have been replaced by the block just formed
            if source.version == from_version {
                source.links[usize::from(link)] =
                    Link { pc, slot: slot as u16, version: target_version };
            }
        }

        Some(slot)
    }

    /// Executes the first `n` micro-ops of `block` (fewer if it is left early)
    #[inline(always)]
    fn jit_exec<Bus: BusInterface>(&mut self, bus: &mut Bus, block: &Block, n: usize) -> Exit {
        let mut i = 0;
        let mut link = 0u8;

        while i < n {
            let op = &block.ops[i];

            if op.kind == K_CALLOUT {
                if op.flags & F_FETCH != 0 {
                    self.jit_fetch(block, op);
                }
                if op.flags & F_SLOT != 0 {
                    self.registers.pc = self.registers.next_pc;
                } else {
                    self.registers.pc = op.addr.wrapping_add(2);
                }
                self.registers.next_pc = self.registers.pc.wrapping_add(2);
                self.registers.next_op_in_delay_slot = false;
                self.data_ctx = AccessContext::Data { pc: op.addr, opcode: op.opcode };

                self.jit_callout(op, bus);
                bus.increment_cycle_counter(1);
                i += 1;

                if bus.should_stop_execution() {
                    return Exit { executed: i, stopped: true, link: LINK_NONE };
                }
                if op.flags & F_MEM != 0 && !block.valid(&self.cache) {
                    return Exit { executed: i, stopped: false, link: LINK_NONE };
                }
                // The registers are authoritative after a call-out: continue only if it went
                // where the block was recorded to go
                if i < n && self.registers.pc != block.ops[i].addr {
                    return Exit { executed: i, stopped: false, link: LINK_NONE };
                }
                link = LINK_NONE;
                continue;
            }

            // Native segment
            let end = usize::from(op.seg_end).min(n);
            let counter = bus.idle_cycle_counter();
            let limit = bus.idle_cycle_limit();
            let mut spent = 0u64;

            if counter + u64::from(op.guard) < limit {
                // No stop can occur before the segment's last instruction
                stat!(s => s.fast_segments += 1);
                while i < end {
                    let o = &block.ops[i];
                    if o.flags & F_FETCH != 0 {
                        self.jit_fetch(block, o);
                    }
                    let alt = self.jit_native::<Bus>(o);
                    stat!(s => s.native_instructions += 1);
                    spent += u64::from(o.cost);
                    i += 1;
                    if alt {
                        spent += u64::from(o.extra);
                        if o.kind == K_BRANCH {
                            bus.increment_cycle_counter(spent);
                            self.data_ctx = AccessContext::Data { pc: o.addr, opcode: o.opcode };
                            return Exit {
                                executed: i,
                                stopped: bus.should_stop_execution(),
                                link: 1,
                            };
                        }
                        link = 1;
                    } else if o.kind == K_DELAYED {
                        link = 0;
                    }
                }
            } else {
                stat!(s => s.checked_segments += 1);
                while i < end {
                    let o = &block.ops[i];
                    if o.flags & F_FETCH != 0 {
                        self.jit_fetch(block, o);
                    }
                    let alt = self.jit_native::<Bus>(o);
                    stat!(s => s.native_instructions += 1);
                    spent += u64::from(o.cost);
                    i += 1;
                    if alt {
                        spent += u64::from(o.extra);
                        if o.kind == K_BRANCH {
                            bus.increment_cycle_counter(spent);
                            self.data_ctx = AccessContext::Data { pc: o.addr, opcode: o.opcode };
                            return Exit {
                                executed: i,
                                stopped: bus.should_stop_execution(),
                                link: 1,
                            };
                        }
                        link = 1;
                    } else if o.kind == K_DELAYED {
                        link = 0;
                    }
                    if counter + spent >= limit {
                        bus.increment_cycle_counter(spent);
                        self.jit_materialize(o);
                        return Exit { executed: i, stopped: true, link: LINK_NONE };
                    }
                }
            }

            bus.increment_cycle_counter(spent);
            if bus.should_stop_execution() {
                self.jit_materialize(&block.ops[i - 1]);
                return Exit { executed: i, stopped: true, link: LINK_NONE };
            }
        }

        if n != 0 && block.ops[n - 1].kind != K_CALLOUT {
            self.jit_materialize(&block.ops[n - 1]);
        }
        let full = n == usize::from(block.n);
        Exit { executed: n, stopped: false, link: if full { link } else { LINK_NONE } }
    }

    /// Runs a native micro-op; the hottest instructions are called directly instead of through
    /// the stored function pointer (both run the same function)
    #[inline(always)]
    fn jit_native<Bus: BusInterface>(&mut self, o: &Uop) -> bool {
        match o.code {
            form::H_NOP => false,
            form::H_BT => form::cond_branch::<Bus, true>(self, o),
            form::H_BF => form::cond_branch::<Bus, false>(self, o),
            form::H_BT_S => form::delayed_cond_branch::<Bus, true>(self, o),
            form::H_BF_S => form::delayed_cond_branch::<Bus, false>(self, o),
            form::H_BRA => form::bra::<Bus>(self, o),
            form::H_CMP_EQ_IMM => {
                self.cmp_eq_imm_r0(o.opcode);
                false
            }
            form::H_TST_IMM => {
                self.tst_imm_r0(o.opcode);
                false
            }
            form::H_CMP_EQ => {
                self.cmp_eq_rm_rn(o.opcode);
                false
            }
            form::H_TST => {
                self.tst_rm_rn(o.opcode);
                false
            }
            form::H_ADD_IMM => {
                self.add_imm_rn(o.opcode);
                false
            }
            form::H_SWAP_B => {
                self.swap_b(o.opcode);
                false
            }
            form::H_DT => {
                self.dt(o.opcode);
                false
            }
            form::H_MOV => {
                self.mov_rm_rn(o.opcode);
                false
            }
            form::H_MOV_IMM => {
                self.mov_b_immediate_rn(o.opcode);
                false
            }
            _ => {
                // SAFETY: native micro-ops are stored as `NativeFn`
                let f = unsafe { std::mem::transmute::<usize, NativeFn>(o.f) };
                f(self, o)
            }
        }
    }

    /// Runs a call-out's interpreter handler; the hottest ones are called directly
    #[inline(always)]
    fn jit_callout<Bus: BusInterface>(&mut self, op: &Uop, bus: &mut Bus) {
        match op.code {
            form::C_MOV_W_GBR_R0 => self.mov_w_disp_gbr_r0(op.opcode, bus),
            form::C_MOV_L_IND_RN => self.mov_l_indirect_rn(op.opcode, bus),
            form::C_MOV_W_IND_RN => self.mov_w_indirect_rn(op.opcode, bus),
            form::C_MOV_L_PC_RN => self.mov_l_immediate_rn(op.opcode, bus),
            form::C_MOV_L_DISP_RN => self.mov_l_rm_displacement_rn(op.opcode, bus),
            form::C_MOV_L_RM_IND => self.mov_l_rm_indirect(op.opcode, bus),
            _ => {
                // SAFETY: call-out handlers were taken from this bus type's opcode table, which
                // `JitCache::ensure_table` keyed the cache on
                let handler = unsafe { std::mem::transmute::<usize, OpcodeFn<Bus>>(op.f) };
                handler(self, op.opcode, bus);
            }
        }
    }

    /// The fetch side effects of an instruction whose fetch may miss the host memo
    #[inline(always)]
    fn jit_fetch(&mut self, block: &Block, op: &Uop) {
        let line = &block.lines[usize::from(op.line)];
        if self.cache.fetch_memo_line() != line.addr {
            self.cache.fetch_hit(line.way, line.entry, line.addr);
        }
    }

    /// Interpreter state after the native micro-op `op` was the last one executed
    #[inline(always)]
    fn jit_materialize(&mut self, op: &Uop) {
        if op.kind == K_DELAYED {
            // `next_pc` and the delay flag were set by the branch
            self.registers.pc = op.addr.wrapping_add(2);
        } else if op.flags & F_SLOT != 0 {
            self.registers.pc = self.registers.next_pc;
            self.registers.next_pc = self.registers.pc.wrapping_add(2);
            self.registers.next_op_in_delay_slot = false;
        } else {
            self.registers.pc = op.addr.wrapping_add(2);
            self.registers.next_pc = op.addr.wrapping_add(4);
            self.registers.next_op_in_delay_slot = false;
        }
        self.data_ctx = AccessContext::Data { pc: op.addr, opcode: op.opcode };
    }
}
