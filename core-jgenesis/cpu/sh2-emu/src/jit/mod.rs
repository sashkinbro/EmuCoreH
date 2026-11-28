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
#[cfg(any(test, feature = "jit-arm64"))]
mod arena;
#[cfg(any(test, feature = "jit-arm64"))]
pub(crate) mod native;
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

static NATIVE: AtomicBool = AtomicBool::new(true);

/// Whether the block engine compiles hot blocks to AArch64 code (builds with `jit-arm64` on
/// aarch64 Linux/Android only; elsewhere this has no effect). On by default; the threaded
/// executor remains the fallback for everything not compiled.
pub fn set_jit_native(enabled: bool) {
    NATIVE.store(enabled, Ordering::Relaxed);
}

/// Threaded runs of a block before it is compiled
#[cfg(any(test, feature = "jit-arm64"))]
const COMPILE_AFTER_R0: u16 = if cfg!(test) { 1 } else { 8 };
#[cfg(any(test, feature = "jit-arm64"))]
const COMPILE_AFTER_R6: u16 = if cfg!(test) { 1 } else { 16 };

#[cfg(feature = "jit-verify")]
pub use verify::{set_jit_verify, set_jit_verify_deep_every};

pub(crate) const MAX_OPS: usize = 32;
pub(crate) const MAX_LINES: usize = 6;
pub(crate) const SETS: usize = 2048;
pub(crate) const WAYS: usize = 4;
pub(crate) const SLOTS: usize = SETS * WAYS;

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
pub(crate) const LINK_NONE: u8 = 2;

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
#[repr(transparent)]
#[derive(Default, Debug)]
pub(crate) struct Seen(AtomicU32);
const _: () = assert!(std::mem::size_of::<Seen>() == 4);

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
    set_victim: Box<[u8; SETS]>,
    #[cfg(any(test, feature = "jit-arm64"))]
    fast_table: Vec<native::FastSlot>,
    // Identifier for the bus type the call-out handlers were taken from
    table_id: usize,
    #[cfg(any(test, feature = "jit-arm64"))]
    pool_id: u64,
    #[cfg(any(test, feature = "jit-arm64"))]
    pool_gen: u64,
    #[cfg(any(test, feature = "jit-arm64"))]
    enter_stub: usize,
    #[cfg(any(test, feature = "jit-arm64"))]
    ctx: Box<native::Ctx>,
}

impl Default for JitCache {
    fn default() -> Self {
        Self {
            table: Vec::new(),
            set_victim: Box::new([0; SETS]),
            #[cfg(any(test, feature = "jit-arm64"))]
            fast_table: Vec::new(),
            table_id: 0,
            #[cfg(any(test, feature = "jit-arm64"))]
            pool_id: 0,
            #[cfg(any(test, feature = "jit-arm64"))]
            pool_gen: 0,
            #[cfg(any(test, feature = "jit-arm64"))]
            enter_stub: 0,
            #[cfg(any(test, feature = "jit-arm64"))]
            ctx: Box::default(),
        }
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

#[cfg(any(test, feature = "jit-arm64"))]
#[inline(always)]
fn fast_slot_index(pc: u32) -> usize {
    (((pc >> 1) ^ (pc >> 10)) as usize) & (native::FAST_SLOTS - 1)
}

#[cfg(any(test, feature = "jit-arm64"))]
#[inline(always)]
fn opcodes_hash(ops: &[Uop]) -> u64 {
    let mut h = 0xcbf2_9ce4_8422_2325u64 ^ (ops.len() as u64);
    for op in ops {
        h ^= u64::from(op.opcode);
        h = h.wrapping_mul(0x0000_0100_0000_01B3);
    }
    h
}

impl JitCache {
    #[inline(always)]
    fn ensure_table(&mut self, table_id: usize) {
        if self.table_id != table_id || self.table.is_empty() {
            #[cfg(any(test, feature = "jit-arm64"))]
            {
                self.ctx.invalidate();
                self.fast_table.clear();
                self.fast_table.resize(native::FAST_SLOTS, native::FastSlot::default());
                self.pool_id = 0;
                self.pool_gen = 0;
                self.enter_stub = 0;
            }
            self.table.clear();
            self.table.resize_with(SLOTS, Block::empty);
            self.set_victim.fill(0);
            self.table_id = table_id;
        }
    }

    #[cfg(any(test, feature = "jit-arm64"))]
    #[inline(always)]
    fn sync_pool(&mut self) {
        let meta = arena::pool::pool_meta();
        if self.pool_id != meta.id || self.pool_gen != meta.epoch {
            for block in &mut self.table {
                block.entry = 0;
                block.runs = 0;
            }
            self.fast_table.fill(native::FastSlot::default());
            self.pool_id = meta.id;
            self.pool_gen = meta.epoch;
            self.enter_stub = meta.enter;
        } else if self.enter_stub == 0 && meta.enter != 0 {
            self.enter_stub = meta.enter;
        }
    }

    #[cfg(any(test, feature = "jit-arm64"))]
    #[inline(always)]
    fn update_fast_slot(&mut self, slot: usize) {
        let block = &self.table[slot];
        if block.entry == 0 {
            return;
        }
        let f = &mut self.fast_table[fast_slot_index(block.start)];
        *f = native::FastSlot {
            pc: block.start,
            seen: block.seen.get(),
            slot: slot as u16,
            n: block.n,
            _pad: [0; 5],
            entry: block.entry,
            block: std::ptr::from_ref(block) as usize,
        };
    }

    #[cfg(any(test, feature = "jit-arm64"))]
    #[inline(always)]
    fn invalidate_fast_slot(&mut self, pc: u32, block_ptr: usize) {
        let f = &mut self.fast_table[fast_slot_index(pc)];
        if f.block == block_ptr {
            f.pc = u32::MAX;
            f.entry = 0;
        }
    }
}

#[inline(always)]
fn set_base(pc: u32) -> usize {
    ((((pc >> 1) ^ (pc >> 12)) as usize) & (SETS - 1)) << 2
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
    /// Blocks compiled to AArch64 code / refused by the compiler
    pub compiled: u64,
    pub compile_rejects: u64,
    /// Block runs in compiled code / deoptimizations to the threaded executor
    pub native_runs: u64,
    pub native_deopts: u64,
    /// Instructions executed in blocks entered by native chaining (without returning to Rust)
    pub native_chained_instructions: u64,
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
    const USE_BLOCKS: bool = true;

    #[inline(always)]
    fn opcode(&mut self, _: &mut Sh2, _: &mut Bus, _: u16) -> bool {
        false
    }

    #[inline(always)]
    fn block(&mut self, cpu: &mut Sh2, bus: &mut Bus, ticks: u64, allow_start_watch: bool) -> (u64, bool) {
        cpu.jit_run(bus, ticks, allow_start_watch)
    }
}

/// How a block run ended
#[derive(Clone, Copy)]
pub(crate) struct Exit {
    pub(crate) executed: usize,
    pub(crate) stopped: bool,
    pub(crate) link: u8,
}

impl Sh2 {
    #[inline(always)]
    pub(super) fn jit_prepare_slice<Bus: BusInterface>(&mut self, bus: &mut Bus) {
        self.jit.ensure_table(Bus::bus_type_id());
        #[cfg(any(test, feature = "jit-arm64"))]
        {
            self.jit.sync_pool();
            self.cache.ensure_entry_tags();
            let cpu_ptr: *mut Sh2 = self;
            let fast_ptr = self.jit.fast_table.as_ptr();
            let ctx = self.jit.ctx.as_mut();
            if ctx.cpu != cpu_ptr {
                ctx.init_static::<Bus>(cpu_ptr, fast_ptr);
            }
            // SAFETY: synchronous execution borrows `bus` exclusively for this slice
            unsafe { ctx.set_bus(std::ptr::from_mut(bus)) };
        }
        #[cfg(not(any(test, feature = "jit-arm64")))]
        let _ = bus;
    }

    #[inline]
    pub(crate) fn execute_jit<Bus: BusInterface>(&mut self, ticks: u64, bus: &mut Bus) {
        #[cfg(feature = "jit-verify")]
        if verify::verify_enabled() {
            return self.execute_verified(ticks, bus);
        }
        self.jit_prepare_slice(bus);
        self.execute_with_body(ticks, bus, &mut Jit);
    }

    #[cfg(test)]
    pub(crate) fn execute_jit_for_test<Bus: BusInterface>(&mut self, ticks: u64, bus: &mut Bus) {
        self.jit_prepare_slice(bus);
        self.execute_with_body(ticks, bus, &mut Jit);
    }

    #[cfg(any(test, feature = "jit-arm64"))]
    #[cold]
    #[inline(never)]
    fn jit_fast_revalidate(&mut self, idx: usize, block_ptr: *const Block, code_gen: u32) -> bool {
        // SAFETY: `block_ptr` points into `self.jit.table`, which is fixed-size after `ensure_table`
        // and disjoint from `fast_table` and `self.cache`.
        let ok = unsafe { &*block_ptr }.valid(&self.cache);
        if ok {
            self.jit.fast_table[idx].seen = code_gen;
        }
        ok
    }

    /// Runs blocks starting at the current pc, following chained exits, for up to `ticks`
    /// instructions. Returns the number executed and whether `should_stop_execution` became
    /// true. Executing nothing is always allowed: the caller then single-steps the interpreter.
    #[inline(always)]
    pub(crate) fn jit_run<Bus: BusInterface>(
        &mut self,
        bus: &mut Bus,
        ticks: u64,
        allow_start_watch: bool,
    ) -> (u64, bool) {
        let pc = self.registers.pc;
        let region = pc >> 29;
        if (region != 0 && region != 6)
            || pc & 1 != 0
            || self.registers.next_op_in_delay_slot
            || (region == 0 && !self.cache.enabled())
        {
            return (0, false);
        }
        debug_assert_ne!(ticks, 0);
        debug_assert!(!*self.idle_recording);

        #[cfg(any(test, feature = "jit-arm64"))]
        {
            debug_assert!(self.cache.entry_tags_synced());
            let raw_watch = self.idle_watch.0.head;
            let watch = if raw_watch == crate::idle::NO_WATCH || self.idle_cannot_skip(bus, ticks) {
                crate::idle::NO_WATCH
            } else {
                raw_watch
            };
            let idx = fast_slot_index(pc);
            let code_gen = self.cache.code_gen();
            // SAFETY: `jit_prepare_slice` allocated `fast_table` with `FAST_SLOTS` elements.
            let fs = unsafe { self.jit.fast_table.get_unchecked(idx) };
            if fs.pc == pc && fs.entry != 0 {
                let fs_seen = fs.seen;
                let target_entry = fs.entry;
                let block_ptr = fs.block as *const Block;
                let bn = usize::from(fs.n);
                if fs_seen == code_gen || self.jit_fast_revalidate(idx, block_ptr, code_gen) {
                    let to_watch = watch.wrapping_sub(pc);
                    let watch_hit = (to_watch != 0 || !allow_start_watch)
                        && to_watch & 1 == 0
                        && ((to_watch >> 1) as usize) < bn;
                    if bn <= ticks as usize && !watch_hit {
                        let enter_stub = self.jit.enter_stub;
                        let cpu_ptr: *mut Sh2 = self;
                        let bus_ptr: *mut Bus = bus;
                        stat!(s => s.block_runs += 1; s.native_runs += 1);
                        // SAFETY: blocks and code store stay immutable while native code runs;
                        // `ctx` is a disjoint heap allocation owned by `self.jit`, and deriving `ctx_ptr`
                        // from `cpu_ptr` keeps `cpu_ptr` and `bus_ptr` valid across the native call.
                        let (exit, last, end_watch) = unsafe {
                            let ctx_ptr: *mut native::Ctx = &raw mut *(*cpu_ptr).jit.0.ctx;
                            (*ctx_ptr).watch = watch;
                            let (exit, last) = native::run_with_ctx(
                                ctx_ptr,
                                cpu_ptr,
                                bus_ptr,
                                block_ptr,
                                bn,
                                target_entry,
                                enter_stub,
                                ticks,
                                arena::Store::CALL,
                            );
                            (exit, last, (*ctx_ptr).watch)
                        };
                        let executed = exit.executed as u64;
                        stat!(s => s.instructions += executed);
                        #[cfg(feature = "jit-verify")]
                        bus.jit_checkpoint(ticks - executed, self);
                        if exit.stopped {
                            return (executed, true);
                        }
                        // Also fast-return on full-slice completion or idle-watch landing so
                        // `jit_run_continue` / `jit_run_loop` are never entered needlessly.
                        if executed == 0 || executed == ticks || self.registers.pc == end_watch {
                            return (executed, false);
                        }
                        return self.jit_run_continue::<Bus>(bus, ticks, executed, exit.link, last);
                    }
                }
            }
        }

        self.jit_run_slow::<Bus>(bus, ticks, allow_start_watch)
    }

    #[cfg(any(test, feature = "jit-arm64"))]
    #[cold]
    #[inline(never)]
    fn jit_run_continue<Bus: BusInterface>(
        &mut self,
        bus: &mut Bus,
        ticks: u64,
        total: u64,
        link: u8,
        last: *const Block,
    ) -> (u64, bool) {
        let prev = (link != LINK_NONE).then(|| {
            // SAFETY: `last` points into `self.jit.table`, which is never resized.
            let slot = unsafe { usize::from((*last).slot) };
            (slot, self.jit.table[slot].version, link)
        });
        self.jit_run_loop::<Bus>(bus, ticks, total, prev, false)
    }

    #[cold]
    #[inline(never)]
    fn jit_run_slow<Bus: BusInterface>(
        &mut self,
        bus: &mut Bus,
        ticks: u64,
        allow_start_watch: bool,
    ) -> (u64, bool) {
        self.jit_run_loop::<Bus>(bus, ticks, 0, None, allow_start_watch)
    }

    #[inline(never)]
    fn jit_run_loop<Bus: BusInterface>(
        &mut self,
        bus: &mut Bus,
        ticks: u64,
        mut total: u64,
        mut prev: Option<(usize, u32, u8)>,
        allow_start_watch: bool,
    ) -> (u64, bool) {
        loop {
            let pc = self.registers.pc;
            stat!(s => if pc >> 29 != 0 && pc >> 29 != 6 {
                s.uncached_entries += 1;
                if pc >> 29 == 1 {
                    s.uncached_regions[((pc >> 25) & 3) as usize] += 1;
                }
            });
            let remaining = ticks - total;
            let raw_watch = self.idle_watch.0.head;
            let watch = if raw_watch == crate::idle::NO_WATCH || self.idle_cannot_skip(bus, remaining) {
                crate::idle::NO_WATCH
            } else {
                raw_watch
            };
            let start_watch_ok = total == 0 && allow_start_watch;
            // A pending delay slot belongs to a branch the interpreter executed; the watched idle
            // loop head is handled by the caller's arrival logic except when skipping is impossible.
            if (pc >> 29 != 0 && pc >> 29 != 6)
                || pc & 1 != 0
                || self.registers.next_op_in_delay_slot
                || (pc == watch && !start_watch_ok)
                || *self.idle_recording
                || total == ticks
            {
                break;
            }

            // Fast path for compiled blocks on first entry (1 L1d cache line in fast_table)
            #[cfg(any(test, feature = "jit-arm64"))]
            let (mut slot, mut version, fast_block): (usize, u32, Option<*const Block>) = if prev.is_none() {
                let fs = &self.jit.fast_table[fast_slot_index(pc)];
                if fs.pc == pc && fs.seen == self.cache.code_gen() && fs.entry != 0 {
                    // SAFETY: `fs.block` points into `self.jit.table`, which is never resized
                    let block_ptr = fs.block as *const Block;
                    let b: &Block = unsafe { &*block_ptr };
                    (usize::from(fs.slot), b.version, Some(block_ptr))
                } else {
                    (0, 0, None)
                }
            } else {
                (0, 0, None)
            };

            #[cfg(any(test, feature = "jit-arm64"))]
            let block_ptr: *const Block = if let Some(bp) = fast_block {
                bp
            } else {
                let Some(s) = self.jit_lookup::<Bus>(pc, prev) else {
                    stat!(s => s.form_misses += 1);
                    break;
                };
                self.jit_prepare_native(s);
                let b: &Block = unsafe { &*std::ptr::from_ref(&self.jit.table[s]) };
                slot = s;
                version = b.version;
                std::ptr::from_ref(b)
            };
            #[cfg(any(test, feature = "jit-arm64"))]
            let block: &Block = unsafe { &*block_ptr };

            #[cfg(not(any(test, feature = "jit-arm64")))]
            let (slot, version, block): (usize, u32, &Block) = {
                let Some(s) = self.jit_lookup::<Bus>(pc, prev) else {
                    stat!(s => s.form_misses += 1);
                    break;
                };
                let b: &Block = unsafe { &*std::ptr::from_ref(&self.jit.table[s]) };
                (s, b.version, b)
            };

            let mut n = usize::from(block.n).min(remaining as usize);
            // Arrival at the watched loop head is handled by the caller. The watch only changes in
            // taken branches, which end the block.
            let to_watch = watch.wrapping_sub(pc);
            if (to_watch != 0 || !start_watch_ok) && to_watch & 1 == 0 && ((to_watch >> 1) as usize) < n {
                n = (to_watch >> 1) as usize;
            }
            if n == 0 {
                break;
            }

            stat!(s => s.block_runs += 1; if prev.is_some() { s.chained_runs += 1 });
            #[cfg(any(test, feature = "jit-arm64"))]
            let exit = if block.entry != 0 && n == usize::from(block.n) {
                stat!(s => s.native_runs += 1);
                let enter_stub = self.jit.enter_stub;
                let block_n = usize::from(block.n);
                let target_entry = block.entry;
                let cpu_ptr: *mut Sh2 = self;
                let bus_ptr: *mut Bus = bus;
                // SAFETY: blocks and code store are not modified while compiled code runs;
                // deriving `ctx_ptr` from `cpu_ptr` keeps `cpu_ptr` and `bus_ptr` valid across the call.
                let (exit, last) = unsafe {
                    let ctx_ptr: *mut native::Ctx = &raw mut *(*cpu_ptr).jit.0.ctx;
                    (*ctx_ptr).watch = watch;
                    native::run_with_ctx(
                        ctx_ptr,
                        cpu_ptr,
                        bus_ptr,
                        block_ptr,
                        block_n,
                        target_entry,
                        enter_stub,
                        remaining,
                        arena::Store::CALL,
                    )
                };
                // Native chaining may have left the block in another slot
                if !std::ptr::eq(last, block_ptr) {
                    // SAFETY: `last` points into the table, which is never resized
                    slot = unsafe { usize::from((*last).slot) };
                    version = self.jit.table[slot].version;
                }
                exit
            } else {
                self.jit_exec(bus, block, n)
            };
            #[cfg(not(any(test, feature = "jit-arm64")))]
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

    /// Counts a run of the block in `slot` and compiles it when it became hot
    #[cfg(any(test, feature = "jit-arm64"))]
    #[inline(always)]
    fn jit_prepare_native(&mut self, slot: usize) {
        if !arena::AVAILABLE || !NATIVE.load(Ordering::Relaxed) {
            return;
        }
        let block = &mut self.jit.table[slot];
        if block.entry != 0 || block.no_native {
            return;
        }
        block.runs += 1;
        let threshold = if block.start >> 29 == 6 { COMPILE_AFTER_R6 } else { COMPILE_AFTER_R0 };
        if block.runs >= threshold {
            self.jit_compile(slot);
        }
    }

    #[cfg(any(test, feature = "jit-arm64"))]
    #[cold]
    #[inline(never)]
    fn jit_compile(&mut self, slot: usize) {
        let jit = &mut *self.jit;
        let pc = jit.table[slot].start;
        let h = opcodes_hash(&jit.table[slot].ops);
        let (published, meta, freshly_compiled) = {
            let block = &jit.table[slot];
            arena::pool::pool_compile_or_get(pc, h, &block.ops, |stubs| native::compile(block, stubs))
        };
        if jit.pool_id != meta.id || jit.pool_gen != meta.epoch {
            for (idx, block) in jit.table.iter_mut().enumerate() {
                if idx != slot {
                    block.entry = 0;
                    block.runs = 0;
                }
            }
            jit.fast_table.fill(native::FastSlot::default());
            jit.pool_id = meta.id;
            jit.pool_gen = meta.epoch;
        }
        if meta.enter != 0 {
            jit.enter_stub = meta.enter;
        }
        match published {
            Some(entry) => {
                if freshly_compiled {
                    stat!(s => s.compiled += 1);
                }
                jit.table[slot].entry = entry;
                jit.update_fast_slot(slot);
            }
            None => {
                stat!(s => s.compile_rejects += 1);
                jit.table[slot].no_native = true;
            }
        }
    }

    /// Finds (or forms) a valid block for `pc`, following and updating the previous exit's link
    #[inline(never)]
    fn jit_lookup<Bus: BusInterface>(&mut self, pc: u32, prev: Option<(usize, u32, u8)>) -> Option<usize> {
        let base = set_base(pc);
        let mut found = None;
        let mut linked = false;
        if let Some((from, from_version, link)) = prev {
            let l = self.jit.table[from].links[usize::from(link)];
            if l.pc == pc {
                let target = &self.jit.table[usize::from(l.slot)];
                if target.version == l.version && target.n != 0 && target.start == pc {
                    found = Some(usize::from(l.slot));
                    linked = true;
                }
            }
            let _ = from_version;
        }

        let code_gen = self.cache.code_gen();
        #[cfg(any(test, feature = "jit-arm64"))]
        let mut fast_dirty = false;
        if let Some(s) = found {
            let cached = &mut self.jit.table[s];
            if cached.seen.get() != code_gen {
                if cached.valid(&self.cache) {
                    #[cfg(any(test, feature = "jit-arm64"))]
                    {
                        fast_dirty = true;
                    }
                } else if cached.refresh(&self.cache) {
                    stat!(s => s.blocks_refreshed += 1);
                    for line in &cached.lines[..usize::from(cached.nlines)] {
                        self.cache.mark_code_line(line.way, line.entry);
                    }
                    #[cfg(any(test, feature = "jit-arm64"))]
                    {
                        fast_dirty = true;
                    }
                } else {
                    found = None;
                    linked = false;
                }
            }
        } else {
            for candidate in base..base + WAYS {
                let cached = &mut self.jit.table[candidate];
                if cached.n != 0 && cached.start == pc {
                    if cached.seen.get() == code_gen {
                        found = Some(candidate);
                    } else if cached.valid(&self.cache) {
                        #[cfg(any(test, feature = "jit-arm64"))]
                        {
                            fast_dirty = true;
                        }
                        found = Some(candidate);
                    } else if cached.refresh(&self.cache) {
                        stat!(s => s.blocks_refreshed += 1);
                        for line in &cached.lines[..usize::from(cached.nlines)] {
                            self.cache.mark_code_line(line.way, line.entry);
                        }
                        #[cfg(any(test, feature = "jit-arm64"))]
                        {
                            fast_dirty = true;
                        }
                        found = Some(candidate);
                    }
                    break;
                }
            }
        }

        let slot = if let Some(s) = found {
            #[cfg(any(test, feature = "jit-arm64"))]
            if fast_dirty {
                self.jit.update_fast_slot(s);
            }
            s
        } else {
            let formed = self.jit_form::<Bus>(pc)?;
            stat!(s => s.blocks_formed += 1);
            for line in &formed.lines[..usize::from(formed.nlines)] {
                self.cache.mark_code_line(line.way, line.entry);
            }
            let slice = &self.jit.table[base..base + WAYS];
            let victim_way = (0..WAYS)
                .find(|&w| slice[w].n == 0 || slice[w].start == pc)
                .or_else(|| (0..WAYS).find(|&w| slice[w].seen.get() != code_gen))
                .or_else(|| (0..WAYS).find(|&w| slice[w].entry == 0))
                .unwrap_or_else(|| {
                    let set_idx = base >> 2;
                    let v = &mut self.jit.set_victim[set_idx];
                    let w = usize::from(*v & 3);
                    *v = (w as u8).wrapping_add(1) & 3;
                    w
                });
            let slot = base + victim_way;
            let cached = &self.jit.table[slot];
            let old_start = cached.start;
            let old_n = cached.n;
            let old_ptr = std::ptr::from_ref(cached) as usize;
            stat!(s => if cached.n != 0 && cached.start != pc { s.slot_conflicts += 1 });
            #[cfg(any(test, feature = "jit-arm64"))]
            if old_n != 0 {
                self.jit.invalidate_fast_slot(old_start, old_ptr);
            }
            #[cfg(not(any(test, feature = "jit-arm64")))]
            let _ = (old_start, old_n, old_ptr);
            let jit = &mut *self.jit;
            let cached = &mut jit.table[slot];
            let version = cached.version.wrapping_add(1);
            *cached = formed;
            cached.version = version;
            cached.slot = slot as u16;
            cached.seen.set(code_gen);
            #[cfg(any(test, feature = "jit-arm64"))]
            {
                let ops = &jit.table[slot].ops;
                let h = opcodes_hash(ops);
                if let Some((entry, meta)) = arena::pool::pool_lookup(pc, h, ops)
                    && meta.id == jit.pool_id
                    && meta.epoch == jit.pool_gen
                {
                    jit.table[slot].entry = entry;
                    jit.enter_stub = meta.enter;
                    jit.update_fast_slot(slot);
                }
            }
            linked = false;
            slot
        };

        if !linked && let Some((from, from_version, link)) = prev {
            let target_version = self.jit.table[slot].version;
            #[cfg(any(test, feature = "jit-arm64"))]
            let target_ptr = std::ptr::from_ref(&self.jit.table[slot]) as usize;
            let source = &mut self.jit.table[from];
            // The previous block may have been replaced by the block just formed
            if source.version == from_version {
                source.links[usize::from(link)] =
                    Link { pc, slot: slot as u16, version: target_version };
                #[cfg(any(test, feature = "jit-arm64"))]
                {
                    source.chain[usize::from(link)] = target_ptr;
                }
            }
        }

        Some(slot)
    }

    /// One call-out micro-op `i` with the interpreter's exact state around it. Returns the exit
    /// if the block must be left after it.
    #[inline(always)]
    pub(crate) fn jit_callout_step<Bus: BusInterface>(
        &mut self,
        bus: &mut Bus,
        block: &Block,
        i: usize,
        n: usize,
    ) -> Option<Exit> {
        let op = &block.ops[i];
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
        let i = i + 1;

        if bus.should_stop_execution() {
            return Some(Exit { executed: i, stopped: true, link: LINK_NONE });
        }
        if op.flags & F_MEM != 0 && !block.valid(&self.cache) {
            return Some(Exit { executed: i, stopped: false, link: LINK_NONE });
        }
        // The registers are authoritative after a call-out: continue only if it went where the
        // block was recorded to go
        if i < n && self.registers.pc != block.ops[i].addr {
            return Some(Exit { executed: i, stopped: false, link: LINK_NONE });
        }
        None
    }

    /// Executes the first `n` micro-ops of `block` (fewer if it is left early)
    #[inline(never)]
    fn jit_exec<Bus: BusInterface>(&mut self, bus: &mut Bus, block: &Block, n: usize) -> Exit {
        self.jit_exec_from(bus, block, 0, n)
    }

    /// Executes micro-ops `start..n` of `block`; `start` must be a segment start or a call-out
    #[inline(never)]
    pub(crate) fn jit_exec_from<Bus: BusInterface>(
        &mut self,
        bus: &mut Bus,
        block: &Block,
        start: usize,
        n: usize,
    ) -> Exit {
        let mut i = start;
        let mut link = 0u8;

        while i < n {
            let op = &block.ops[i];

            if op.kind == K_CALLOUT {
                if let Some(exit) = self.jit_callout_step(bus, block, i, n) {
                    return exit;
                }
                i += 1;
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
    pub(crate) fn jit_fetch(&mut self, block: &Block, op: &Uop) {
        let line = &block.lines[usize::from(op.line)];
        if self.cache.fetch_memo_line() != line.addr {
            self.cache.fetch_hit(line.way, line.entry, line.addr);
        }
    }

    /// Interpreter state after the native micro-op `op` was the last one executed
    #[inline(always)]
    pub(crate) fn jit_materialize(&mut self, op: &Uop) {
        self.jit_materialize_fields(op.addr, op.opcode, op.kind, op.flags);
    }

    #[inline(always)]
    pub(crate) fn jit_materialize_fields(&mut self, addr: u32, opcode: u16, kind: u8, flags: u8) {
        if kind == K_DELAYED {
            // `next_pc` and the delay flag were set by the branch
            self.registers.pc = addr.wrapping_add(2);
        } else if flags & F_SLOT != 0 {
            self.registers.pc = self.registers.next_pc;
            self.registers.next_pc = self.registers.pc.wrapping_add(2);
            self.registers.next_op_in_delay_slot = false;
        } else {
            self.registers.pc = addr.wrapping_add(2);
            self.registers.next_pc = addr.wrapping_add(4);
            self.registers.next_op_in_delay_slot = false;
        }
        self.data_ctx = AccessContext::Data { pc: addr, opcode };
    }
}
