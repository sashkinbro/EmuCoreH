//! Predecoded block cache ("threaded interpreter") for cached SH-2 code.
//!
//! Phase (a) of the SH-2 JIT, see `docs/SH2-JIT-DESIGN.md`. A block is the straight-line run of
//! instructions starting at a program counter that is currently resident in the CPU cache: every
//! instruction word was read out of cache RAM and its handler resolved once. Executing a block
//! performs exactly what `Sh2::execute_with_body` performs per instruction (fetch side effects,
//! register/delay-slot bookkeeping, handler, cycle charge, stop check), minus the repeated
//! fetch, opcode-table lookup and handler selection.
//!
//! Exactness rests on three facts:
//! * Block contents are only trusted while the per-line change counters of every cache line the
//!   block spans (see `cache::LineGens`) are unchanged, so a fetch would hit the same way and
//!   return the same word. The counters are re-validated after every instruction that can touch
//!   the cache; an invalid block is abandoned and the interpreter continues with the very next
//!   instruction.
//! * Instruction fetch hits have two side effects, the pseudo-LRU update and the host fetch memo.
//!   Both are re-applied through `CpuCache::fetch_hit` exactly when the interpreter's fetch would
//!   have applied them (memo does not cover the line).
//! * Control flow is verified, not assumed: after every instruction that is not provably
//!   straight-line (conditional branches, exceptions, anything unrecognized) the program counter
//!   must equal the address the block was recorded at, so a taken branch or an exception simply
//!   leaves the block.
//!
//! The block cache is host-only state (`HostCache`): never serialized, dropped on clone. Buses
//! that run it must not be debugger buses (`BusInterface::IDLE_LOOPS` contract), which is why the
//! per-instruction `check_execute` hook is not called.

use crate::bus::BusInterface;
use crate::instructions::{OpcodeFn, OpcodeTable};
use crate::{AccessContext, ExecutionBody, Sh2};
use std::fmt::{self, Debug, Formatter};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};

static ENABLED: AtomicBool = AtomicBool::new(false);

/// Selects the predecoded block executor for buses that support idle-loop skipping (i.e. real,
/// non-debugger buses). Output is bit-identical either way; this only changes host speed.
pub fn set_predecode_enabled(enabled: bool) {
    ENABLED.store(enabled, Ordering::Relaxed);
}

#[inline(always)]
pub(crate) fn predecode_enabled() -> bool {
    ENABLED.load(Ordering::Relaxed)
}

const MAX_OPS: usize = 16;
const MAX_LINES: usize = 4;
const SLOTS: usize = 2048;

// Op flags
// Reads or writes through the CPU cache (anything not provably register-only)
const MEM: u8 = 1;
// May leave straight-line flow (conditional branch, exception, unrecognized opcode)
const CTL: u8 = 2;

#[derive(Clone, Copy)]
struct Op {
    // `OpcodeFn<Bus>` of the bus type the cache was built for (see `BlockCache::ensure_table`)
    handler: usize,
    pc: u32,
    opcode: u16,
    line: u8,
    flags: u8,
}

#[derive(Clone, Copy)]
struct LineRef {
    // Address bits 4-31 of the line
    addr: u32,
    gen_value: u32,
    way: u8,
    entry: u8,
}

// Interior-mutable validation stamp (relaxed atomic only to keep `Sh2` `Sync`)
#[derive(Default)]
struct Seen(AtomicU32);

impl Clone for Seen {
    fn clone(&self) -> Self {
        Self(AtomicU32::new(self.get()))
    }
}

impl Seen {
    #[inline(always)]
    fn get(&self) -> u32 {
        self.0.load(Ordering::Relaxed)
    }

    #[inline(always)]
    fn set(&self, value: u32) {
        self.0.store(value, Ordering::Relaxed);
    }
}

#[derive(Clone)]
struct Block {
    start: u32,
    conf: u32,
    // Value of the cache's global change counter at the last successful validation
    seen: Seen,
    n: u8,
    nlines: u8,
    lines: [LineRef; MAX_LINES],
    // A delayed branch at the end of a full block still takes its delay slot along
    ops: [Op; MAX_OPS + 1],
}

const EMPTY_OP: Op = Op { handler: 0, pc: 0, opcode: 0, line: 0, flags: 0 };
const EMPTY_LINE: LineRef = LineRef { addr: 0, gen_value: 0, way: 0, entry: 0 };

fn empty_block() -> Block {
    Block {
        start: 0,
        conf: 0,
        seen: Seen::default(),
        n: 0,
        nlines: 0,
        lines: [EMPTY_LINE; MAX_LINES],
        ops: [EMPTY_OP; MAX_OPS + 1],
    }
}

impl Block {
    /// Whether every line the block was recorded from is still exactly as recorded
    #[inline(always)]
    fn valid(&self, cache: &crate::cache::CpuCache) -> bool {
        self.seen.get() == cache.any_gen() || self.revalidate(cache)
    }

    #[cold]
    #[inline(never)]
    fn revalidate(&self, cache: &crate::cache::CpuCache) -> bool {
        if cache.conf_gen() != self.conf {
            return false;
        }
        for line in &self.lines[..usize::from(self.nlines)] {
            if cache.line_gen(line.way, line.entry) != line.gen_value {
                return false;
            }
        }
        self.seen.set(cache.any_gen());
        true
    }
}

pub(crate) struct BlockCache {
    table: Vec<Block>,
    // Address of the opcode table the handlers were taken from
    table_id: usize,
}

impl Default for BlockCache {
    fn default() -> Self {
        Self { table: Vec::new(), table_id: 0 }
    }
}

// Cloned CPUs (rewind/run-ahead snapshots) start with no blocks: they are cheap to rebuild
impl Clone for BlockCache {
    fn clone(&self) -> Self {
        Self::default()
    }
}

impl Debug for BlockCache {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        f.debug_struct("BlockCache").finish_non_exhaustive()
    }
}

impl BlockCache {
    /// Handlers are stored as raw addresses of the bus-specific functions of one opcode table
    #[inline(always)]
    pub(crate) fn ensure_table(&mut self, table_id: usize) {
        if self.table_id != table_id || self.table.is_empty() {
            self.table.clear();
            self.table.resize(SLOTS, empty_block());
            self.table_id = table_id;
        }
    }
}

#[inline(always)]
fn slot_index(pc: u32) -> usize {
    (((pc >> 1) ^ (pc >> 12)) as usize) & (SLOTS - 1)
}

pub(crate) struct Predecoded;

impl<Bus: BusInterface> ExecutionBody<Bus> for Predecoded {
    const IDLE_LOOPS: bool = true;

    #[inline(always)]
    fn opcode(&mut self, _: &mut Sh2, _: &mut Bus, _: u16) -> bool {
        false
    }

    #[inline(always)]
    fn block(&mut self, cpu: &mut Sh2, bus: &mut Bus, ticks: u64, _allow_start_watch: bool) -> (u64, bool) {
        cpu.run_block(bus, ticks)
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum Flow {
    Continue,
    // Delayed branch: the next instruction (its delay slot) is the last of the block
    Delayed,
    End,
}

pub(crate) fn flow(opcode: u16) -> Flow {
    match opcode >> 12 {
        0xA | 0xB => Flow::Delayed,
        0x8 => match (opcode >> 8) & 0xF {
            0xD | 0xF => Flow::Delayed,
            _ => Flow::Continue,
        },
        0x4 => match opcode & 0xF0FF {
            0x400B | 0x402B => Flow::Delayed,
            _ => Flow::Continue,
        },
        0x0 => match opcode & 0xF0FF {
            0x0003 | 0x0023 | 0x000B | 0x002B => Flow::Delayed,
            0x001B => Flow::End,
            _ => Flow::Continue,
        },
        0xC if (opcode >> 8) & 0xF == 0x3 => Flow::End,
        _ => Flow::Continue,
    }
}

/// Instructions that never go through `read_*`/`write_*` and so never touch the CPU cache.
/// Anything not positively identified here is treated as touching it.
pub(crate) fn is_register_only(opcode: u16) -> bool {
    let low = opcode & 0xF;
    match opcode >> 12 {
        0x7 | 0xA | 0xB | 0xE => true,
        0x6 => low == 0x3 || low >= 0x7,
        0x3 => low != 0x1 && low != 0x9,
        0x2 => low >= 0x7,
        0x4 => matches!(
            opcode & 0xFF,
            0x00 | 0x01
                | 0x04
                | 0x05
                | 0x08
                | 0x09
                | 0x0A
                | 0x0B
                | 0x0E
                | 0x10
                | 0x11
                | 0x15
                | 0x18
                | 0x19
                | 0x1A
                | 0x1E
                | 0x20
                | 0x21
                | 0x24
                | 0x25
                | 0x28
                | 0x29
                | 0x2A
                | 0x2B
                | 0x2E
        ),
        0x0 => {
            matches!(
                opcode & 0xFF,
                0x02 | 0x03
                    | 0x07
                    | 0x08
                    | 0x09
                    | 0x0A
                    | 0x0B
                    | 0x12
                    | 0x18
                    | 0x19
                    | 0x1A
                    | 0x22
                    | 0x23
                    | 0x28
                    | 0x29
                    | 0x2A
            ) || low == 0x7
        }
        0x8 => matches!((opcode >> 8) & 0xF, 0x8 | 0x9 | 0xB | 0xD | 0xF),
        0xC => matches!((opcode >> 8) & 0xF, 0x7 | 0x8 | 0x9 | 0xA | 0xB),
        _ => false,
    }
}

/// Instructions that touch the cache but are known to continue with the next instruction (or
/// with the delay slot) and never raise an exception
fn is_straight_memory(opcode: u16) -> bool {
    let low = opcode & 0xF;
    match opcode >> 12 {
        0x1 | 0x5 | 0x9 | 0xD => true,
        0x2 | 0x6 => matches!(low, 0x0 | 0x1 | 0x2 | 0x4 | 0x5 | 0x6),
        0x0 => matches!(low, 0x4 | 0x5 | 0x6 | 0xC | 0xD | 0xE | 0xF),
        0x4 => {
            low == 0xF
                || matches!(
                    opcode & 0xFF,
                    0x02 | 0x03 | 0x06 | 0x07 | 0x12 | 0x13 | 0x16 | 0x17 | 0x1B | 0x22 | 0x23
                        | 0x26 | 0x27
                )
        }
        0x8 => matches!((opcode >> 8) & 0xF, 0x0 | 0x1 | 0x4 | 0x5),
        0xC => matches!((opcode >> 8) & 0xF, 0x0 | 0x1 | 0x2 | 0x4 | 0x5 | 0x6 | 0xC..=0xF),
        _ => false,
    }
}

/// Instructions whose successor is not guaranteed to be the next sequential fetch (the delay
/// slot of a delayed branch counts as sequential): taken-able `BT`/`BF`, `TRAPA`, and everything
/// not positively known to be straight-line, including illegal opcodes, `SLEEP` and `RTE`
fn leaves_straight_line(opcode: u16) -> bool {
    matches!(opcode >> 8, 0x89 | 0x8B | 0xC3)
        || !(is_register_only(opcode) || is_straight_memory(opcode))
}

/// The handler the interpreter would call, with the opcode groups that share a decode closure
/// resolved to their instruction directly
pub(crate) fn handler_for<Bus: BusInterface>(opcode: u16, table: &OpcodeTable<Bus>) -> OpcodeFn<Bus> {
    match opcode >> 8 {
        0x80 => |cpu, op, bus| cpu.mov_b_r0_rn_displacement(op, bus),
        0x81 => |cpu, op, bus| cpu.mov_w_r0_rn_displacement(op, bus),
        0x84 => |cpu, op, bus| cpu.mov_b_rm_displacement_r0(op, bus),
        0x85 => |cpu, op, bus| cpu.mov_w_rm_displacement_r0(op, bus),
        0x88 => |cpu, op, _| cpu.cmp_eq_imm_r0(op),
        0x89 => |cpu, op, bus| cpu.bt(op, bus),
        0x8B => |cpu, op, bus| cpu.bf(op, bus),
        0x8D => |cpu, op, bus| cpu.bt_s(op, bus),
        0x8F => |cpu, op, bus| cpu.bf_s(op, bus),
        0xC0 => |cpu, op, bus| cpu.mov_b_r0_disp_gbr(op, bus),
        0xC1 => |cpu, op, bus| cpu.mov_w_r0_disp_gbr(op, bus),
        0xC2 => |cpu, op, bus| cpu.mov_l_r0_disp_gbr(op, bus),
        0xC4 => |cpu, op, bus| cpu.mov_b_disp_gbr_r0(op, bus),
        0xC5 => |cpu, op, bus| cpu.mov_w_disp_gbr_r0(op, bus),
        0xC6 => |cpu, op, bus| cpu.mov_l_disp_gbr_r0(op, bus),
        0xC7 => |cpu, op, _| cpu.mova(op),
        0xC8 => |cpu, op, _| cpu.tst_imm_r0(op),
        0xC9 => |cpu, op, _| cpu.and_imm_r0(op),
        0xCA => |cpu, op, _| cpu.xor_imm_r0(op),
        0xCB => |cpu, op, _| cpu.or_imm_r0(op),
        _ => table.decode(opcode),
    }
}

impl Sh2 {
    /// Records the block starting at `pc` from cache-resident code only, without side effects
    fn form_block<Bus: BusInterface>(&self, pc: u32) -> Option<Block> {
        let table = Bus::opcode_table();
        let mut block = empty_block();
        block.start = pc;
        block.conf = self.cache.conf_gen();
        block.seen.set(self.cache.any_gen());

        let mut addr = pc;
        let mut delay_slot_next = false;
        loop {
            let n = usize::from(block.n);
            if n >= MAX_OPS && !delay_slot_next {
                break;
            }
            if addr >> 29 != 0 {
                break;
            }
            let Some((way, entry, opcode)) = self.cache.peek_fetch(addr) else {
                break;
            };

            let line_addr = addr & !0xF;
            let nlines = usize::from(block.nlines);
            if nlines == 0 || block.lines[nlines - 1].addr != line_addr {
                if nlines == MAX_LINES {
                    break;
                }
                block.lines[nlines] = LineRef {
                    addr: line_addr,
                    gen_value: self.cache.line_gen(way, entry),
                    way,
                    entry,
                };
                block.nlines += 1;
            }

            let mut flags = 0;
            if !is_register_only(opcode) {
                flags |= MEM;
            }
            if leaves_straight_line(opcode) {
                flags |= CTL;
            }
            block.ops[n] = Op {
                handler: handler_for::<Bus>(opcode, table) as usize,
                pc: addr,
                opcode,
                line: block.nlines - 1,
                flags,
            };
            block.n += 1;
            addr = addr.wrapping_add(2);

            if delay_slot_next {
                break;
            }
            match flow(opcode) {
                Flow::Continue => {}
                Flow::Delayed => delay_slot_next = true,
                Flow::End => break,
            }
        }

        (block.n != 0).then_some(block)
    }

    /// Executes up to `ticks` instructions of the block at the current pc. Returns the number
    /// executed and whether `should_stop_execution` became true. Executing nothing is always
    /// allowed: the caller then single-steps the interpreter.
    #[inline]
    pub(crate) fn run_block<Bus: BusInterface>(&mut self, bus: &mut Bus, ticks: u64) -> (u64, bool) {
        let pc = self.registers.pc;
        // A delay slot at the entry is the second half of a branch the interpreter executed: its
        // successor is the branch target, which a straight-line block cannot know
        if pc >> 29 != 0 || pc & 1 != 0 || self.registers.next_op_in_delay_slot {
            return (0, false);
        }

        let slot = slot_index(pc);
        let cached = &self.blocks.table[slot];
        let block: *const Block = if cached.n != 0 && cached.start == pc && cached.valid(&self.cache)
        {
            cached
        } else {
            let Some(formed) = self.form_block::<Bus>(pc) else {
                return (0, false);
            };
            self.blocks.table[slot] = formed;
            &self.blocks.table[slot]
        };
        // SAFETY: the table is only modified here, never by instruction handlers, bus callbacks
        // or nested executions (those run other `Sh2` values), and it is not resized after
        // allocation. The block therefore stays untouched until this function returns.
        let block = unsafe { &*block };

        let mut limit = u64::from(block.n).min(ticks) as usize;
        // Arrival at a watched loop head is handled by the caller. The watch only changes in
        // handlers of taken branches, after which this function leaves the block anyway.
        let watch_idx = (self.idle_watch.0.head.wrapping_sub(pc) >> 1) as usize;
        if self.idle_watch.0.head.wrapping_sub(pc) & 1 == 0 && watch_idx < limit {
            limit = watch_idx;
        }

        let mut i = 0;
        while i < limit {
            let op = &block.ops[i];

            let line = &block.lines[usize::from(op.line)];
            if self.cache.fetch_memo_line() != line.addr {
                self.cache.fetch_hit(line.way, line.entry, line.addr);
            }

            self.registers.pc = self.registers.next_pc;
            self.registers.next_pc = self.registers.pc.wrapping_add(2);
            self.registers.next_op_in_delay_slot = false;
            if op.flags != 0 {
                self.data_ctx = AccessContext::Data { pc: op.pc, opcode: op.opcode };
            }

            // SAFETY: `handler` was taken from this bus type's opcode table, which
            // `BlockCache::ensure_table` keyed the cache on
            let handler = unsafe { std::mem::transmute::<usize, OpcodeFn<Bus>>(op.handler) };
            handler(self, op.opcode, bus);
            bus.increment_cycle_counter(1);
            i += 1;

            if bus.should_stop_execution() {
                return (self.finish_block(block, i), true);
            }
            if op.flags == 0 {
                continue;
            }
            if op.flags & MEM != 0 && !block.valid(&self.cache) {
                break;
            }
            if op.flags & CTL != 0 && i < limit && self.registers.pc != block.ops[i].pc {
                break;
            }
        }

        (self.finish_block(block, i), false)
    }

    /// `data_ctx` is only written before instructions that can observe it; it must still hold
    /// the last executed instruction when the block is left
    #[inline(always)]
    fn finish_block(&mut self, block: &Block, executed: usize) -> u64 {
        if executed != 0 {
            let last = &block.ops[executed - 1];
            self.data_ctx = AccessContext::Data { pc: last.pc, opcode: last.opcode };
        }
        executed as u64
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bus::OpSize;
    use crate::registers::Sh2Registers;

    const RAM_BASE: u32 = 0x0600_0000;
    const RAM_LEN: usize = 0x4000;
    const READ_CYCLES: u64 = 3;
    const WRITE_CYCLES: u64 = 2;
    const LINE_CYCLES: u64 = 8;

    type LogEntry = (u64, bool, u8, u32, u32);

    #[derive(Clone)]
    struct TestBus {
        mem: Vec<u8>,
        counter: u64,
        limit: u64,
        irq: u8,
        log: Vec<LogEntry>,
    }

    fn in_ram(address: u32, len: u32) -> bool {
        address & !(RAM_LEN as u32 - 1) == RAM_BASE && address & (len - 1) == 0
    }

    impl TestBus {
        fn ram_read(&self, address: u32, size: u8) -> u32 {
            let base = (address as usize) & (RAM_LEN - 1);
            let len = 1usize << size;
            (0..len).fold(0, |acc, i| (acc << 8) | u32::from(self.mem[base + i]))
        }

        fn ram_write(&mut self, address: u32, size: u8, value: u32) {
            let base = (address as usize) & (RAM_LEN - 1);
            let len = 1usize << size;
            for i in 0..len {
                self.mem[base + i] = (value >> (8 * (len - 1 - i))) as u8;
            }
        }
    }

    impl BusInterface for TestBus {
        type DebugView<'a> = crate::debug::DummySh2Debugger;

        fn read<const SIZE: u8>(&mut self, address: u32, _: AccessContext) -> u32 {
            let value = if in_ram(address, 1 << SIZE) {
                self.ram_read(address, SIZE)
            } else {
                address.wrapping_mul(0x9E37_79B9).rotate_left(11) & OpSize::mask::<SIZE>()
            };
            self.log.push((self.counter, false, SIZE, address, value));
            self.counter += READ_CYCLES;
            value
        }

        fn read_cache_line(&mut self, address: u32, _: AccessContext) -> [u16; 8] {
            self.log.push((self.counter, false, 9, address, 0));
            self.counter += LINE_CYCLES;
            std::array::from_fn(|i| {
                let a = address + 2 * i as u32;
                if in_ram(a, 2) {
                    self.ram_read(a, OpSize::WORD) as u16
                } else {
                    a.wrapping_mul(0x9E37_79B9).rotate_left(11) as u16
                }
            })
        }

        fn write<const SIZE: u8>(&mut self, address: u32, value: u32, _: AccessContext) {
            self.log.push((self.counter, true, SIZE, address, value & OpSize::mask::<SIZE>()));
            self.counter += WRITE_CYCLES;
            if in_ram(address, 1 << SIZE) {
                self.ram_write(address, SIZE, value);
            }
        }

        fn reset(&self) -> bool {
            false
        }
        fn interrupt_level(&self) -> u8 {
            self.irq
        }
        fn dma_request_0(&self) -> bool {
            false
        }
        fn dma_request_1(&self) -> bool {
            false
        }
        fn acknowledge_dreq_1(&mut self) {}
        fn serial_rx(&mut self) -> Option<u8> {
            None
        }
        fn serial_tx(&mut self, _: u8) {}
        fn increment_cycle_counter(&mut self, n: u64) {
            self.counter += n;
        }
        fn should_stop_execution(&self) -> bool {
            self.counter >= self.limit
        }

        fn opcode_table<'a>() -> &'a OpcodeTable<Self> {
            static TABLE: std::sync::LazyLock<OpcodeTable<TestBus>> =
                std::sync::LazyLock::new(OpcodeTable::new);
            &TABLE
        }

        const IDLE_LOOPS: bool = true;

        fn idle_cycle_counter(&self) -> u64 {
            self.counter
        }
        fn idle_cycle_limit(&self) -> u64 {
            self.limit
        }
        fn idle_read_supported(&self, address: u32, size: u8) -> bool {
            in_ram(address, 1 << size)
        }
        fn idle_write_supported(&self, address: u32, size: u8) -> bool {
            in_ram(address, 1 << size)
        }
        fn idle_replay_read(&mut self, address: u32, size: u8, _: u64) -> Option<u32> {
            in_ram(address, 1 << size).then(|| self.ram_read(address, size))
        }
        fn idle_write_is_noop(&mut self, address: u32, size: u8, value: u32) -> bool {
            in_ram(address, 1 << size) && self.ram_read(address, size) == value
        }
    }

    struct Rng(u64);
    impl Rng {
        fn next(&mut self) -> u64 {
            self.0 ^= self.0 << 13;
            self.0 ^= self.0 >> 7;
            self.0 ^= self.0 << 17;
            self.0
        }
        fn below(&mut self, n: u64) -> u64 {
            (self.next() >> 11) % n
        }
        fn pick(&mut self, options: &[u16]) -> u16 {
            options[self.below(options.len() as u64) as usize]
        }
    }

    fn random_opcode(rng: &mut Rng) -> u16 {
        let n = (rng.below(16) as u16) << 8;
        let m = (rng.below(16) as u16) << 4;
        let imm = (rng.next() & 0xFF) as u16;
        let disp = (rng.below(13) as i16 - 6) as u16;
        let d4 = (rng.next() & 0xF) as u16;
        match rng.below(100) {
            0..=9 => 0x6003 | n | m,
            10..=14 => 0x300C | n | m,
            15..=17 => 0x7000 | n | imm,
            18..=21 => 0xE000 | n | imm,
            22..=26 => {
                rng.pick(&[
                    0x2009, 0x200B, 0x200A, 0x2008, 0x3000, 0x3002, 0x3003, 0x3006, 0x3007, 0x3008,
                    0x300A, 0x300E,
                ]) | n
                    | m
            }
            27..=29 => {
                rng.pick(&[
                    0x4000, 0x4001, 0x4004, 0x4005, 0x4008, 0x4009, 0x4018, 0x4019, 0x4010, 0x4011,
                    0x4015, 0x4021, 0x4024, 0x4025,
                ]) | n
            }
            30..=32 => {
                rng.pick(&[
                    0x6008, 0x6009, 0x600C, 0x600D, 0x600E, 0x600F, 0x600B, 0x6007, 0x200C, 0x200D,
                ]) | n
                    | m
            }
            33..=39 => rng.pick(&[0x2000, 0x2001, 0x2002, 0x2004, 0x2005, 0x2006]) | n | m,
            40..=46 => rng.pick(&[0x6000, 0x6001, 0x6002, 0x6004, 0x6005, 0x6006]) | n | m,
            47..=49 => 0x1000 | n | m | d4,
            50..=52 => 0x5000 | n | m | d4,
            53..=54 => rng.pick(&[0x8000, 0x8100, 0x8400, 0x8500]) | m | d4,
            55..=56 => rng.pick(&[0xC000, 0xC100, 0xC200, 0xC400, 0xC500, 0xC600]) | imm,
            57..=58 => 0x9000 | n | (imm & 0x3F),
            59..=60 => 0xD000 | n | (imm & 0x3F),
            61 => 0xC700 | (imm & 0x3F),
            62..=63 => rng.pick(&[0x0004, 0x0005, 0x0006, 0x000C, 0x000D, 0x000E]) | n | m,
            64 => rng.pick(&[0x000F, 0x400F, 0x401B]) | n | m,
            65..=69 => rng.pick(&[0x8B00, 0x8900]) | (disp & 0xFF),
            70..=72 => rng.pick(&[0x8F00, 0x8D00]) | (disp & 0xFF),
            73..=74 => rng.pick(&[0xA000, 0xB000]) | (disp & 0xFFF),
            75 => rng.pick(&[0x402B, 0x400B, 0x0023, 0x0003]) | n,
            76 => 0x000B,
            77 => rng.pick(&[0x0009, 0x0008, 0x0018, 0x0028, 0x0019]),
            78 => rng.pick(&[0xC800, 0xC900, 0xCA00, 0xCB00, 0x8800]) | imm,
            79 => rng.pick(&[0xCC00, 0xCD00, 0xCE00, 0xCF00]) | imm,
            80 => {
                rng.pick(&[
                    0x400E, 0x401E, 0x402E, 0x4007, 0x4017, 0x4027, 0x0002, 0x0012, 0x0022, 0x4003,
                    0x4013, 0x4023,
                ]) | n
            }
            81 => {
                rng.pick(&[
                    0x400A, 0x401A, 0x402A, 0x4006, 0x4016, 0x4026, 0x000A, 0x001A, 0x002A, 0x4002,
                    0x4012, 0x4022,
                ]) | n
            }
            82..=83 => {
                rng.pick(&[
                    0x3004, 0x2007, 0x300D, 0x3005, 0x0007, 0x200F, 0x200E, 0x3009, 0x3001,
                ]) | n
                    | m
            }
            84 => rng.pick(&[0xC300, 0x002B, 0x001B]) | (imm & 0x7F),
            85 => 0x0029 | n,
            _ => rng.next() as u16,
        }
    }

    fn pointer(rng: &mut Rng) -> u32 {
        // Occasionally point into the cache data array (on-chip RAM), where programs started
        // from the array then modify their own code
        if rng.below(16) == 0 {
            return 0xC000_0000 + (rng.below(0x800) as u32) * 2;
        }
        let base = if rng.below(4) == 0 { 0x2600_0000 } else { RAM_BASE };
        let span = if rng.below(4) == 0 { RAM_LEN as u64 / 2 } else { 0x100 };
        base + (rng.below(span) as u32) * 2
    }

    fn build(seed: u64) -> (Sh2, TestBus) {
        let mut rng = Rng(seed.wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1);
        let mut bus =
            TestBus { mem: vec![0; RAM_LEN], counter: 0, limit: 0, irq: 0, log: Vec::new() };
        for i in 0..RAM_LEN / 2 {
            let op = random_opcode(&mut rng);
            bus.mem[2 * i] = (op >> 8) as u8;
            bus.mem[2 * i + 1] = op as u8;
        }
        let mut cpu = Sh2::new("predecode-test".into());
        cpu.reset_pending = false;
        cpu.trace_log_enabled = false;
        let alias = if rng.below(3) == 0 { 0x2000_0000 } else { 0 };
        let mut start = RAM_BASE + (rng.below(0x100) as u32) * 2 + alias;
        // Code in the cache data array used as RAM
        if rng.below(4) == 0 {
            for i in 0..0x800u32 {
                let op = random_opcode(&mut rng);
                cpu.cache.write_data_array_u16(0xC000_0000 + 2 * i, op);
            }
            start = 0xC000_0000 + (rng.below(0x700) as u32) * 2;
        }
        cpu.registers.pc = start;
        cpu.registers.next_pc = start + 2;
        cpu.registers.sr.interrupt_mask = if rng.below(2) == 0 { 15 } else { 3 };
        for r in 0..16 {
            cpu.registers.gpr[r] = pointer(&mut rng);
        }
        cpu.registers.gbr = pointer(&mut rng);
        cpu.registers.vbr = RAM_BASE + 0x3000;
        if rng.below(8) != 0 {
            cpu.cache.write_control(1 | ((rng.below(2) as u8) << 3));
        }
        (cpu, bus)
    }

    fn state(cpu: &Sh2) -> Vec<u8> {
        bincode::encode_to_vec(cpu, bincode::config::standard()).unwrap()
    }

    struct Outcome {
        states: Vec<u8>,
        regs: Vec<(u64, u64, Sh2Registers)>,
        mem: Vec<u8>,
        log: Vec<LogEntry>,
        counter: u64,
    }

    fn run(seed: u64, predecode: bool) -> Outcome {
        run_with(seed, if predecode { Backend::Predecode } else { Backend::Interpreter })
    }

    #[derive(Clone, Copy, PartialEq, Eq)]
    enum Backend {
        Interpreter,
        Predecode,
        Jit,
    }

    fn run_with(seed: u64, backend: Backend) -> Outcome {
        let (mut cpu, mut bus) = build(seed);
        let mut rng = Rng(seed ^ 0xDEAD_BEEF_1234_5678 | 1);
        let mut states = Vec::new();
        let mut regs = Vec::new();
        for _ in 0..60 {
            if rng.below(4) == 0 {
                let r = rng.below(16) as usize;
                cpu.registers.gpr[r] = pointer(&mut rng);
            }
            if rng.below(6) == 0 {
                cpu.registers.sr.t = !cpu.registers.sr.t;
            }
            bus.irq = if rng.below(10) == 0 { rng.below(16) as u8 } else { 0 };
            bus.limit = bus.counter + 1 + rng.below(300);
            let ticks = 1 + rng.below(60);
            match backend {
                Backend::Predecode => {
                    cpu.blocks.ensure_table(std::ptr::from_ref(TestBus::opcode_table()) as usize);
                    cpu.execute_with_body(ticks, &mut bus, &mut Predecoded);
                }
                Backend::Jit => cpu.execute_jit_for_test(ticks, &mut bus),
                Backend::Interpreter => {
                    cpu.execute_with_body(ticks, &mut bus, &mut crate::Interpreter);
                }
            }
            states.extend(state(&cpu));
            regs.push((ticks, bus.counter, cpu.registers.clone()));
        }
        Outcome { states, regs, mem: bus.mem, log: bus.log, counter: bus.counter }
    }

    #[test]
    fn jit_blocks_match_interpreter_exactly() {
        compare_backends(Backend::Jit, "JIT_SEEDS");
    }

    #[test]
    fn predecoded_blocks_match_interpreter_exactly() {
        compare_backends(Backend::Predecode, "PREDECODE_SEEDS");
    }

    fn compare_backends(backend: Backend, seeds_var: &str) {
        let seeds: u64 =
            std::env::var(seeds_var).ok().and_then(|v| v.parse().ok()).unwrap_or(400);
        let mut skipped = 0;
        let first: u64 = std::env::var("PREDECODE_FIRST").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
        for seed in first..=seeds {
            // Wild register values can hit unimplemented internal registers, which panics in
            // the interpreter too; such programs are not comparable
            std::panic::set_hook(Box::new(|_| {}));
            let reference = std::panic::catch_unwind(|| run(seed, false));
            let _ = std::panic::take_hook();
            let Ok(expected) = reference else {
                skipped += 1;
                continue;
            };
            let actual = run_with(seed, backend);
            if expected.states != actual.states {
                let i = expected.regs.iter().zip(&actual.regs).position(|(a, b)| a.2 != b.2 || a.1 != b.1);
                if let Some(i) = i {
                    panic!(
                        "slice {i} diverged, seed {seed}
prev {:?}
expected {:?}
actual   {:?}",
                        expected.regs.get(i.wrapping_sub(1)),
                        expected.regs[i],
                        actual.regs[i]
                    );
                }
            }
            assert!(expected.states == actual.states, "cpu state diverged, seed {seed}");
            assert!(expected.mem == actual.mem, "memory diverged, seed {seed}");
            if expected.log != actual.log {
                let i = expected.log.iter().zip(&actual.log).position(|(a, b)| a != b);
                panic!(
                    "bus log diverged, seed {seed}, first diff at {i:?} ({} vs {} events)",
                    expected.log.len(),
                    actual.log.len()
                );
            }
            assert_eq!(expected.counter, actual.counter, "cycle counter diverged, seed {seed}");
        }
        println!("compared {} programs, skipped {skipped}", seeds - skipped);
        if backend == Backend::Jit {
            println!("{:?}", crate::jit_stats());
        }
        assert!(skipped < seeds / 2, "too many programs hit unimplemented registers");
    }
}
