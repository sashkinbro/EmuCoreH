//! AArch64 block compiler (phase b): whole blocks as machine code.
//!
//! A compiled block is `extern "C" fn(*mut Ctx) -> u64`. Register-only instructions and branches
//! are straight-line code (`a64_translate` for the ALU part); every segment starts with the
//! static-cycle guard (`counter + guard < limit`, else deoptimize to the threaded executor at that
//! micro-op) and ends with one static cycle add and stop check. Call-outs go through a
//! monomorphized trampoline that runs exactly the threaded executor's call-out step
//! (`Sh2::jit_callout_step`), with the cycle counter synchronized to the bus around it.
//!
//! Host registers: `x19 = &mut Sh2Registers`, `x20 = &mut Ctx`, `x21` cycle counter, `x22` cycle
//! limit, `x23 = &fetch memo line`. Guest registers and `T` live in `Sh2Registers` (memory
//! operands): nothing has to be spilled around call-outs, and every exit leaves the register file
//! exact. `pc`/`next_pc`/delay flag are written only by branches; the remaining exit state and
//! `data_ctx` are materialized by the Rust runner from the returned micro-op count, exactly as the
//! threaded executor does.
//!
//! Code runs natively on aarch64 Linux/Android (feature `jit-arm64`) and in the test-only AArch64
//! simulator on other hosts, so the same compiled code is fuzz-tested against the interpreter on
//! x86.

use super::a64::{Asm, Cond, LR, R, SP, Size::W, Size::X, ZR};
use super::a64_translate::{REGS, translate};
use super::form::Block;
use super::{
    Exit, F_FETCH, F_NOTE, F_SLOT, K_BRANCH, K_CALLOUT, K_DELAYED, K_NATIVE, LINK_NONE, Uop,
};
use crate::bus::{AccessContext, BusInterface};
use crate::registers::{Sh2Registers, StatusRegister};
use crate::Sh2;
use std::mem::offset_of;

#[repr(C)]
pub(crate) struct Ctx {
    regs: *mut Sh2Registers,
    counter: u64,
    limit: u64,
    memo: *const u32,
    tramp: usize,
    cpu: *mut Sh2,
    bus: *mut (),
    block: *const Block,
    n: u64,
    stopped: u64,
    /// Instructions left in the slice after the current block (chaining budget)
    ticks: u64,
    /// Instructions executed by blocks left through native chaining
    total: u64,
    watch: *const u32,
    code_gen: *const u32,
}

/// Native chaining record of one block exit (RW data, written by the Rust dispatcher)
#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct Chain {
    pub(crate) block: usize,
    pub(crate) version: u32,
    pad: u32,
}

impl Chain {
    pub(crate) fn new(block: usize, version: u32) -> Self {
        Self { block, version, pad: 0 }
    }
}

const CTX_REGS: u32 = 0;
const CTX_COUNTER: u32 = 8;
const CTX_LIMIT: u32 = 16;
const CTX_MEMO: u32 = 24;
const CTX_TRAMP: u32 = 32;
const CTX_BLOCK: u32 = 56;
const CTX_N: u32 = 64;
const CTX_TICKS: u32 = 80;
const CTX_TOTAL: u32 = 88;
const CTX_WATCH: u32 = 96;
const CTX_CODE_GEN: u32 = 104;
/// Byte offset of the chaining entry (after the 10-instruction prologue)
const CHAIN_ENTRY: u32 = 40;
const CTX_CPU: u32 = 40;
const CPU: R = R(24);

// Trampoline requests
const T_CALLOUT: u64 = 0;
const T_FETCH: u64 = 1;
const T_NOTE: u64 = 2;

// Block exit statuses (high half of the return value; low half = micro-ops executed)
const S_END: u64 = 0;
const S_TAKEN: u64 = 1;
const S_STOP: u64 = 2;
const S_DEOPT: u64 = 3;
const S_CEXIT: u64 = 4;

const CTX: R = R(20);
const COUNTER: R = R(21);
const LIMIT: R = R(22);
const MEMO: R = R(23);
const T0: R = R(9);
const T1: R = R(10);

fn reg_off(field: usize) -> u32 {
    field as u32
}

fn gpr(n: usize) -> u32 {
    reg_off(offset_of!(Sh2Registers, gpr) + 4 * n)
}

fn t_off() -> u32 {
    reg_off(offset_of!(Sh2Registers, sr) + offset_of!(StatusRegister, t))
}

fn rn(opcode: u16) -> usize {
    usize::from((opcode >> 8) & 0xF)
}

fn emit_tramp(a: &mut Asm, kind: u64, index: usize) {
    a.mov(X, R(0), CTX);
    let arg = (kind << 32) | index as u64;
    a.movz(X, R(1), arg as u16, 0);
    a.movk(X, R(1), (arg >> 32) as u16, 32);
    a.ldr(X, R(16), CTX, CTX_TRAMP);
    a.blr(R(16));
}

/// Stores the delayed branch target in `T0` to `next_pc` and sets the delay flag
fn emit_delayed_target(a: &mut Asm) {
    a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, next_pc)));
    a.movz(W, T0, 1, 0);
    a.strb(T0, REGS, reg_off(offset_of!(Sh2Registers, next_op_in_delay_slot)));
}

fn emit_pr(a: &mut Asm, op: &Uop) {
    a.mov_imm32(T0, op.addr.wrapping_add(4));
    a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, pr)));
}

/// The fetch memo check of micro-op `i` (fetch hit side effects through the trampoline)
fn emit_fetch_check(a: &mut Asm, block: &Block, i: usize) {
    let line = &block.lines[usize::from(block.ops[i].line)];
    let skip = a.new_label();
    a.ldr(W, T0, MEMO, 0);
    a.mov_imm32(T1, line.addr);
    a.cmp(W, T0, T1);
    a.b_cond(Cond::Eq, skip);
    emit_tramp(a, T_FETCH, i);
    a.bind(skip);
}

/// A load the compiler inlines for cache hits: address source, size in bytes, destination
#[derive(Clone, Copy)]
pub(crate) struct InlineRead {
    /// Base register (`None`: GBR) and displacement, or a static address
    base: Option<usize>,
    gbr: bool,
    disp: u32,
    size: u32,
    dest: usize,
}

/// Loads whose interpreter handler is exactly `Rn = read(address)` (sign-extended for words) and
/// whose address does not depend on the program counter at run time
pub(crate) fn inline_read(op: &Uop) -> Option<InlineRead> {
    let o = op.opcode;
    let n = rn(o);
    let m = usize::from((o >> 4) & 0xF);
    let slot = op.flags & F_SLOT != 0;
    let next_pc = op.addr.wrapping_add(4);
    // In a delay slot the exit state after the slow path (registers authoritative) and after the
    // fast path (materialized from the micro-op) are not interchangeable: never inline there
    if slot {
        return None;
    }
    Some(match o >> 12 {
        0x6 if o & 0xF == 2 => InlineRead { base: Some(m), gbr: false, disp: 0, size: 4, dest: n },
        0x6 if o & 0xF == 1 => InlineRead { base: Some(m), gbr: false, disp: 0, size: 2, dest: n },
        0x5 => InlineRead { base: Some(m), gbr: false, disp: u32::from(o & 0xF) << 2, size: 4, dest: n },
        0xC if (o >> 8) & 0xF == 5 => InlineRead { base: None, gbr: true, disp: u32::from(o & 0xFF) << 1, size: 2, dest: 0 },
        0xC if (o >> 8) & 0xF == 6 => InlineRead { base: None, gbr: true, disp: u32::from(o & 0xFF) << 2, size: 4, dest: 0 },
        0xD if !slot => InlineRead { base: None, gbr: false, disp: (next_pc & !3).wrapping_add(u32::from(o & 0xFF) << 2), size: 4, dest: n },
        0x9 if !slot => InlineRead { base: None, gbr: false, disp: next_pc.wrapping_add(u32::from(o & 0xFF) << 1), size: 2, dest: n },
        _ => return None,
    })
}

/// Cache-hit read exactly as `CpuCache::cache_read` (memo invalidation of the entry, way search
/// from way 3 down, LRU update, big-endian word layout); anything else branches to `slow`
fn emit_cached_read(a: &mut Asm, read: InlineRead, slow: super::a64::Label) {
    let layout = crate::cache::CpuCache::native_layout();
    let cache = offset_of!(Sh2, cache);
    let off = |field: usize| (cache + field) as u32;
    let idle_recording = (offset_of!(Sh2, idle_recording) + offset_of!(jgenesis_common::hostcache::HostCache<bool>, 0)) as u32;
    let (addr, x10, x11, x12, x13, x14, x15, x16, x17) = (T0, T1, R(11), R(12), R(13), R(14), R(15), R(16), R(17));

    // Address
    match (read.base, read.gbr) {
        (Some(m), _) => {
            a.ldr(W, addr, REGS, gpr(m));
            if read.disp != 0 {
                a.add_imm(W, addr, addr, read.disp);
            }
        }
        (None, true) => {
            a.ldr(W, addr, REGS, reg_off(offset_of!(Sh2Registers, gbr)));
            a.mov_imm32(x10, read.disp);
            a.add(W, addr, addr, x10);
        }
        (None, false) => a.mov_imm32(addr, read.disp),
    }
    // Recording an idle-loop iteration, uncached region, cache disabled: interpreter
    a.ldrb(x10, CPU, idle_recording);
    a.cbnz(W, x10, slow);
    a.lsr_imm(W, x10, addr, 29);
    a.cbnz(W, x10, slow);
    a.ldrb(x10, CPU, off(layout.enabled));
    a.cbz(W, x10, slow);
    // Entry and tag
    a.lsr_imm(W, x11, addr, 4);
    assert!(a.and_imm(W, x11, x11, 63));
    assert!(a.and_imm(W, x12, addr, 0x1FFF_FFFF));
    a.lsr_imm(W, x12, x12, 10);
    a.ldr(X, x13, CPU, off(layout.ways));
    let hits: Vec<_> = (0..4).map(|_| a.new_label()).collect();
    for way in (0..4).rev() {
        let next = a.new_label();
        let base = (way * layout.way_size) as u32;
        a.ldr(X, x14, x13, base + layout.way_valid as u32);
        a.lsrv(X, x14, x14, x11);
        a.tbz(x14, 0, next);
        a.add_shifted(X, x15, x13, x11, super::a64::Shift::Lsl, 2);
        a.ldr(W, x15, x15, base + layout.way_tags as u32);
        a.cmp(W, x15, x12);
        a.b_cond(Cond::Eq, hits[way]);
        a.bind(next);
    }
    a.b(slow);

    let join = a.new_label();
    for way in (0..4).rev() {
        a.bind(hits[way]);
        // Fetch memo invalidation of the entry
        let keep = a.new_label();
        a.ldr(W, x16, CPU, off(layout.memo_line));
        a.lsr_imm(W, x16, x16, 4);
        assert!(a.and_imm(W, x16, x16, 63));
        a.cmp(W, x16, x11);
        a.b_cond(Cond::Ne, keep);
        a.movn(W, x16, 0, 0);
        a.str(W, x16, CPU, off(layout.memo_line));
        a.bind(keep);
        // Pseudo-LRU update (`update_lru_bits`)
        let (and_mask, or_mask): (u32, u32) = match way {
            0 => (!0b111000 & 0xFF, 0),
            1 => (!0b000110 & 0xFF, 0b100000),
            2 => (!0b000001 & 0xFF, 0b010100),
            _ => (0xFF, 0b001011),
        };
        a.ldr(X, x16, CPU, off(layout.lru));
        a.ldrb_reg(x17, x16, x11);
        a.mov_imm32(x15, and_mask);
        a.and(W, x17, x17, x15);
        a.mov_imm32(x15, or_mask);
        a.orr(W, x17, x17, x15);
        a.strb_reg(x17, x16, x11);
        // Cache RAM byte offset: way << 10 | entry << 4 | (address & 0xC or 0xE)
        assert!(a.and_imm(W, x17, addr, if read.size == 4 { 0xC } else { 0xE }));
        a.add_shifted(W, x17, x17, x11, super::a64::Shift::Lsl, 4);
        if way != 0 {
            a.add_imm(W, x17, x17, (way as u32) << 10);
        }
        a.b(join);
    }
    a.bind(join);
    a.ldr(X, x16, CPU, off(layout.ram));
    if read.size == 4 {
        // Two host u16 words holding big-endian halves: high word first
        a.ldr_w_reg(x15, x16, x17, false);
        a.ror_imm(W, x15, x15, 16);
    } else {
        a.ldrh_reg(x15, x16, x17, false);
        a.sxth(W, x15, x15);
    }
    a.str(W, x15, REGS, gpr(read.dest));
}

type ExitFn<'a> = dyn FnMut(&mut Asm, usize, u64, u8, Option<(usize, u32)>) -> super::a64::Label + 'a;

/// Code of native micro-op `i` without its cycle charge; a taken `BT`/`BF` charges `taken_cost`
/// (the cycles not yet charged up to and including it) plus its extra and leaves the block
fn emit_native(a: &mut Asm, block: &Block, i: usize, region0: bool, taken_cost: u32, exit: &mut ExitFn<'_>) -> Option<()> {
    let op = &block.ops[i];
    if region0 && op.flags & F_FETCH != 0 {
        emit_fetch_check(a, block, i);
    }

    match op.kind {
        K_NATIVE => {
            if op.opcode >> 8 == 0xC7 {
                // MOVA: value resolved at formation time
                a.mov_imm32(T0, op.imm);
                a.str(W, T0, REGS, gpr(0));
            } else if !translate(a, op.opcode) {
                return None;
            }
        }
        K_BRANCH => {
            let not_taken = a.new_label();
            a.ldrb(T0, REGS, t_off());
            // BT (0x89) is taken when T is set, BF (0x8B) when clear
            if op.opcode >> 8 == 0x89 {
                a.cbz(W, T0, not_taken);
            } else {
                a.cbnz(W, T0, not_taken);
            }
            a.mov_imm32(T0, op.imm);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, pc)));
            a.add_imm(W, T0, T0, 2);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, next_pc)));
            if op.flags & F_NOTE != 0 {
                emit_tramp(a, T_NOTE, i);
            }
            a.add_imm(X, COUNTER, COUNTER, taken_cost + u32::from(op.extra));
            let out = exit(a, i + 1, S_TAKEN, 1, Some((1, op.imm)));
            a.b(out);
            a.bind(not_taken);
        }
        K_DELAYED => {
            let opcode = op.opcode;
            match opcode >> 12 {
                0xA | 0xB => {
                    if opcode >> 12 == 0xB {
                        emit_pr(a, op);
                    }
                    a.mov_imm32(T0, op.imm);
                    emit_delayed_target(a);
                    if op.flags & F_NOTE != 0 {
                        emit_tramp(a, T_NOTE, i);
                    }
                }
                0x8 => {
                    // BT/S (0x8D), BF/S (0x8F)
                    let untaken = a.new_label();
                    let join = a.new_label();
                    a.ldrb(T0, REGS, t_off());
                    if opcode >> 8 == 0x8D {
                        a.cbz(W, T0, untaken);
                    } else {
                        a.cbnz(W, T0, untaken);
                    }
                    a.mov_imm32(T0, op.imm);
                    emit_delayed_target(a);
                    if op.flags & F_NOTE != 0 {
                        emit_tramp(a, T_NOTE, i);
                    }
                    a.add_imm(X, COUNTER, COUNTER, u32::from(op.extra));
                    a.b(join);
                    a.bind(untaken);
                    a.mov_imm32(T0, op.addr.wrapping_add(4));
                    a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, next_pc)));
                    a.bind(join);
                }
                0x0 if opcode & 0xFF == 0x0B => {
                    // RTS
                    a.ldr(W, T0, REGS, reg_off(offset_of!(Sh2Registers, pr)));
                    emit_delayed_target(a);
                }
                0x0 => {
                    // BSRF (0x03) / BRAF (0x23): target = A + 4 + Rn
                    a.ldr(W, T0, REGS, gpr(rn(opcode)));
                    if opcode & 0xFF == 0x03 {
                        emit_pr(a, op);
                        a.ldr(W, T0, REGS, gpr(rn(opcode)));
                    }
                    a.mov_imm32(T1, op.imm);
                    a.add(W, T0, T0, T1);
                    emit_delayed_target(a);
                }
                _ => {
                    // JSR (0x0B) / JMP (0x2B)
                    if opcode & 0xFF == 0x0B {
                        emit_pr(a, op);
                    }
                    a.ldr(W, T0, REGS, gpr(rn(opcode)));
                    emit_delayed_target(a);
                }
            }
        }
        _ => unreachable!(),
    }

    Some(())
}

/// Machine code for `block`, or `None` if it contains an instruction the compiler does not
/// translate (the block then stays in the threaded executor)
pub(crate) fn compile(block: &Block) -> Option<Vec<u32>> {
    let n = usize::from(block.n);
    let region0 = block.start >> 29 == 0;
    let mut a = Asm::new();
    // (label, return value: link << 40 | status << 32 | executed, chaining (link, successor pc))
    let mut exits: Vec<(super::a64::Label, u64, Option<(usize, u32)>)> = Vec::new();
    let mut exit = |a: &mut Asm, executed: usize, status: u64, link: u8, chain: Option<(usize, u32)>| {
        let label = a.new_label();
        exits.push((label, (u64::from(link) << 40) | (status << 32) | executed as u64, chain));
        label
    };

    // Prologue
    a.stp_pre(R(29), LR, SP, -64);
    a.stp(R(19), R(20), SP, 16);
    a.stp(R(21), R(22), SP, 32);
    a.stp(R(23), R(24), SP, 48);
    a.mov(X, CTX, R(0));
    a.ldr(X, REGS, CTX, CTX_REGS);
    a.ldr(X, COUNTER, CTX, CTX_COUNTER);
    a.ldr(X, LIMIT, CTX, CTX_LIMIT);
    a.ldr(X, MEMO, CTX, CTX_MEMO);
    a.ldr(X, CPU, CTX, CTX_CPU);

    let mut i = 0;
    while i < n {
        let op = &block.ops[i];

        if op.kind == K_CALLOUT {
            let done = a.new_label();
            if let Some(read) = inline_read(op) {
                if region0 && op.flags & F_FETCH != 0 {
                    emit_fetch_check(&mut a, block, i);
                }
                let slow = a.new_label();
                emit_cached_read(&mut a, read, slow);
                a.add_imm(X, COUNTER, COUNTER, 1);
                a.cmp(X, COUNTER, LIMIT);
                let out = exit(&mut a, i + 1, S_STOP, LINK_NONE, None);
                a.b_cond(Cond::Hs, out);
                a.b(done);
                a.bind(slow);
            }
            a.str(X, COUNTER, CTX, CTX_COUNTER);
            emit_tramp(&mut a, T_CALLOUT, i);
            a.ldr(X, COUNTER, CTX, CTX_COUNTER);
            a.ldr(X, LIMIT, CTX, CTX_LIMIT);
            let out = exit(&mut a, i + 1, S_CEXIT, LINK_NONE, None);
            a.cbnz(X, R(0), out);
            a.bind(done);
            i += 1;
            continue;
        }

        // Native segment: a fast copy under the static guard, and a checked copy with the
        // interpreter's per-instruction stop check for when the guard fails (near the limit)
        let end = usize::from(op.seg_end);
        let seg_cost: u32 = block.ops[i..end].iter().map(|o| u32::from(o.cost)).sum();
        let checked = a.new_label();
        let next = a.new_label();
        a.add_imm(X, T0, COUNTER, u32::from(op.guard));
        a.cmp(X, T0, LIMIT);
        a.b_cond(Cond::Hs, checked);
        for j in i..end {
            emit_native(&mut a, block, j, region0, seg_cost, &mut exit)?;
        }
        a.add_imm(X, COUNTER, COUNTER, seg_cost);
        a.cmp(X, COUNTER, LIMIT);
        let out = exit(&mut a, end, S_STOP, LINK_NONE, None);
        a.b_cond(Cond::Hs, out);
        a.b(next);

        a.bind(checked);
        for j in i..end {
            let cost = u32::from(block.ops[j].cost);
            emit_native(&mut a, block, j, region0, cost, &mut exit)?;
            a.add_imm(X, COUNTER, COUNTER, cost);
            a.cmp(X, COUNTER, LIMIT);
            let out = exit(&mut a, j + 1, S_STOP, LINK_NONE, None);
            a.b_cond(Cond::Hs, out);
        }
        a.bind(next);
        i = end;
    }

    // Block end: link index and chaining successor, following the threaded executor's rules
    let last = &block.ops[n - 1];
    let branch = (n >= 2 && last.flags & F_SLOT != 0).then(|| &block.ops[n - 2]);
    let delay_off = reg_off(offset_of!(Sh2Registers, next_op_in_delay_slot));
    if last.kind == K_CALLOUT || branch.is_some_and(|b| b.kind != K_DELAYED) {
        // Ends in a call-out, or in the slot of a call-out delayed branch (RTE): no link
        let out = exit(&mut a, n, S_END, LINK_NONE, None);
        a.b(out);
    } else if let Some(b) = branch.or((last.kind == K_DELAYED).then_some(last)) {
        // With the slot in the block the static successor can be chained; a delayed branch
        // whose slot is not in the block leaves it pending and is never chained
        let has_slot = branch.is_some();
        let chain = |k: usize, pc: u32| has_slot.then_some((k, pc));
        match b.opcode >> 12 {
            0xA | 0xB => {
                let out = exit(&mut a, n, S_END, 0, chain(0, b.imm));
                a.b(out);
            }
            0x8 => {
                let taken = exit(&mut a, n, S_END, 1, chain(1, b.imm));
                let untaken = exit(&mut a, n, S_END, 0, chain(0, b.addr.wrapping_add(4)));
                a.ldrb(T0, REGS, delay_off);
                a.cbnz(W, T0, taken);
                a.b(untaken);
            }
            _ => {
                let out = exit(&mut a, n, S_END, 1, None);
                a.b(out);
            }
        }
    } else {
        let out = exit(&mut a, n, S_END, 0, Some((0, last.addr.wrapping_add(2))));
        a.b(out);
    }

    let epilogue = a.new_label();
    for (label, value, chain) in exits {
        a.bind(label);
        if let Some((k, next_pc)) = chain {
            let fail = a.new_label();
            let executed = value as u32;
            if (value >> 32) & 0xFF == S_TAKEN {
                // A taken branch charged its cycles without a stop check
                a.cmp(X, COUNTER, LIMIT);
                a.b_cond(Cond::Hs, fail);
            }
            let record = std::ptr::from_ref(&block.chain[k]) as u64;
            a.movz(X, R(11), record as u16, 0);
            a.movk(X, R(11), (record >> 16) as u16, 16);
            a.movk(X, R(11), (record >> 32) as u16, 32);
            a.movk(X, R(11), (record >> 48) as u16, 48);
            a.ldr(X, R(12), R(11), 0);
            a.cbz(X, R(12), fail);
            a.ldr(W, R(13), R(11), 8);
            a.ldr(W, R(14), R(12), offset_of!(Block, version) as u32);
            a.cmp(W, R(13), R(14));
            a.b_cond(Cond::Ne, fail);
            a.ldr(X, R(15), CTX, CTX_CODE_GEN);
            a.ldr(W, R(15), R(15), 0);
            a.ldr(W, R(14), R(12), offset_of!(Block, seen) as u32);
            a.cmp(W, R(14), R(15));
            a.b_cond(Cond::Ne, fail);
            // Link indices are shared with dynamic exits: the record must be for this successor
            a.ldr(W, R(14), R(12), offset_of!(Block, start) as u32);
            a.mov_imm32(R(15), next_pc);
            a.cmp(W, R(14), R(15));
            a.b_cond(Cond::Ne, fail);
            a.ldr(X, R(16), R(12), offset_of!(Block, entry) as u32);
            a.cbz(X, R(16), fail);
            a.ldrb(R(13), R(12), offset_of!(Block, n) as u32);
            a.ldr(X, R(9), CTX, CTX_TICKS);
            a.cmp(X, R(9), R(13));
            a.b_cond(Cond::Lo, fail);
            // The successor must stop short of the watched idle-loop head: watch - pc >= 2n
            a.ldr(X, R(15), CTX, CTX_WATCH);
            a.ldr(W, R(15), R(15), 0);
            a.mov_imm32(R(14), next_pc);
            a.sub(W, R(15), R(15), R(14));
            a.add(W, R(10), R(13), R(13));
            a.cmp(W, R(15), R(10));
            a.b_cond(Cond::Lo, fail);
            // Commit: budget, executed count, current block, pending delay flag cleared
            a.sub(X, R(9), R(9), R(13));
            a.str(X, R(9), CTX, CTX_TICKS);
            a.ldr(X, R(10), CTX, CTX_TOTAL);
            a.add_imm(X, R(10), R(10), executed);
            a.str(X, R(10), CTX, CTX_TOTAL);
            a.str(X, R(12), CTX, CTX_BLOCK);
            a.str(X, R(13), CTX, CTX_N);
            a.strb(ZR, REGS, delay_off);
            a.add_imm(X, R(16), R(16), CHAIN_ENTRY);
            a.br(R(16));
            a.bind(fail);
        }
        a.movz(X, R(0), value as u16, 0);
        a.movk(X, R(0), (value >> 32) as u16, 32);
        a.b(epilogue);
    }
    a.bind(epilogue);
    a.str(X, COUNTER, CTX, CTX_COUNTER);
    a.ldp(R(23), R(24), SP, 48);
    a.ldp(R(21), R(22), SP, 32);
    a.ldp(R(19), R(20), SP, 16);
    a.ldp_post(R(29), LR, SP, 64);
    a.ret();
    Some(a.finish())
}

/// Called from generated code. Runs on the `Sh2`/bus the runner was entered with.
extern "C" fn tramp<Bus: BusInterface>(ctx: *mut Ctx, arg: u64) -> u64 {
    // SAFETY: `ctx` is the runner's live context; its pointers are the runner's exclusive
    // borrows, not used by the runner while generated code runs
    unsafe {
        let ctx = &mut *ctx;
        let cpu = &mut *ctx.cpu;
        let block = &*ctx.block;
        let i = arg as u32 as usize;
        match arg >> 32 {
            T_CALLOUT => {
                let bus = &mut *ctx.bus.cast::<Bus>();
                let now = bus.idle_cycle_counter();
                bus.increment_cycle_counter(ctx.counter - now);
                let exit = cpu.jit_callout_step(bus, block, i, ctx.n as usize);
                ctx.counter = bus.idle_cycle_counter();
                ctx.limit = bus.idle_cycle_limit();
                match exit {
                    None => 0,
                    Some(exit) => {
                        ctx.stopped = u64::from(exit.stopped);
                        1
                    }
                }
            }
            T_FETCH => {
                cpu.jit_fetch(block, &block.ops[i]);
                0
            }
            _ => {
                cpu.note_backward_branch::<Bus>(block.ops[i].imm);
                0
            }
        }
    }
}

#[allow(dead_code)]
fn end_link(block: &Block, cpu: &Sh2) -> u8 {
    let mut link = 0;
    for op in block.ops.iter() {
        match op.kind {
            K_CALLOUT => link = LINK_NONE,
            K_DELAYED => {
                link = match op.opcode >> 12 {
                    0xA | 0xB => 0,
                    // BT/S, BF/S: taken iff the delay flag is still set (before materialization)
                    0x8 => u8::from(cpu.registers.next_op_in_delay_slot),
                    _ => 1,
                }
            }
            _ => {}
        }
    }
    link
}

/// Runs compiled `entry` for the whole `block`
///
/// # Safety
/// `cpu`, `bus` and `block` must be valid and not otherwise accessed during the call; `entry` must
/// be the compiled code of `block` from `call`'s code store.
#[inline(never)]
pub(crate) unsafe fn run<Bus: BusInterface>(
    cpu: *mut Sh2,
    bus: *mut Bus,
    block: *const Block,
    entry: usize,
    ticks: u64,
    call: unsafe fn(usize, *mut Ctx) -> u64,
) -> (Exit, *const Block) {
    unsafe {
        let n = usize::from((*block).n);
        let mut ctx = Ctx {
            regs: &raw mut (*cpu).registers,
            counter: (*bus).idle_cycle_counter(),
            limit: (*bus).idle_cycle_limit(),
            memo: (*cpu).cache.fetch_memo_line_ptr(),
            tramp: tramp::<Bus> as *const () as usize,
            cpu,
            bus: bus.cast(),
            block,
            n: n as u64,
            stopped: 0,
            ticks: ticks - n as u64,
            total: 0,
            watch: &raw const (*cpu).idle_watch.0.0,
            code_gen: (*cpu).cache.code_gen_ptr(),
        };
        let result = call(entry, &raw mut ctx);
        let block = ctx.block;
        let (bus, cpu, block_ref) = (&mut *bus, &mut *cpu, &*block);
        let now = bus.idle_cycle_counter();
        bus.increment_cycle_counter(ctx.counter - now);
        super::stat!(s => s.native_chained_instructions += ctx.total);
        let mut exit = run_exit(cpu, bus, block_ref, result, &ctx);
        exit.executed += ctx.total as usize;
        (exit, block)
    }
}

unsafe fn run_exit<Bus: BusInterface>(cpu: &mut Sh2, bus: &mut Bus, block: &Block, result: u64, ctx: &Ctx) -> Exit {
    {
        let n = usize::from(block.n);
        let executed = result as u32 as usize;
        let link = ((result >> 40) & 0xFF) as u8;
        match (result >> 32) & 0xFF {
            S_END => {
                let last = &block.ops[n - 1];
                // An inlined load may have taken its cache-hit path: same state as its call-out
                if last.kind != K_CALLOUT || inline_read(last).is_some() {
                    cpu.jit_materialize(last);
                }
                Exit { executed: n, stopped: false, link }
            }
            S_TAKEN => {
                let op = &block.ops[executed - 1];
                cpu.data_ctx = AccessContext::Data { pc: op.addr, opcode: op.opcode };
                Exit { executed, stopped: bus.should_stop_execution(), link: 1 }
            }
            S_STOP => {
                cpu.jit_materialize(&block.ops[executed - 1]);
                Exit { executed, stopped: true, link: LINK_NONE }
            }
            S_DEOPT => {
                super::stat!(s => s.native_deopts += 1);
                cpu.jit_exec_from(bus, block, executed, n)
            }
            _ => Exit { executed, stopped: ctx.stopped != 0, link: LINK_NONE },
        }
    }
}

/// Whether `op` kinds are all supported (cheap pre-check before compiling)
#[allow(dead_code)]
pub(crate) fn compilable(block: &Block) -> bool {
    block.ops.iter().all(|op| op.kind != K_NATIVE || op.opcode >> 8 == 0xC7 || {
        let mut a = Asm::new();
        translate(&mut a, op.opcode)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inline_cached_reads_match_the_cache() {
        let mut seed = 0x9E37_79B9_7F4A_7C15u64;
        let mut next = move || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            seed
        };
        for round in 0..3000 {
            let mut cpu = Sh2::new("inline-read-test".into());
            cpu.cache.write_control(1);
            for _ in 0..48 {
                let line = 0x0600_0000 + ((next() as u32) & 0x3FF0);
                let words: [u16; 8] = std::array::from_fn(|_| next() as u16);
                let _ = cpu.cache.replace(line, words);
            }
            let size = if next() & 1 == 0 { 4 } else { 2 };
            let address = 0x0600_0000 + ((next() as u32) & 0x3FFE & if size == 4 { !3 } else { !1 });
            cpu.registers.gpr[3] = address;
            let opcode = if size == 4 { 0x6532 } else { 0x6531 }; // mov.l/mov.w @R3,R5
            let op = Uop { opcode, ..super::super::EMPTY_UOP };
            let read = inline_read(&op).unwrap();
            let mut reference = cpu.clone();
            let expected = if size == 4 {
                reference.cache.read_u32(address)
            } else {
                reference.cache.read_u16(address).map(|v| v as i16 as u32)
            };

            let mut a = Asm::new();
            let slow = a.new_label();
            emit_cached_read(&mut a, read, slow);
            a.movz(X, R(0), 1, 0);
            a.ret();
            a.bind(slow);
            a.movz(X, R(0), 0, 0);
            a.ret();
            let code = a.finish();
            let mut sim = super::super::a64_sim::Sim::new();
            sim.set_x(19, (&raw mut cpu.registers) as u64);
            sim.set_x(24, (&raw mut cpu) as u64);
            let hit = unsafe { sim.call(code.as_ptr(), 0, &mut |_, _, _| unreachable!()) } == 1;
            assert_eq!(hit, expected.is_some(), "round {round}: hit/miss differs at {address:08X}");
            if let Some(value) = expected {
                assert_eq!(cpu.registers.gpr[5], value, "round {round}: value at {address:08X}");
                let state = |c: &Sh2| bincode::encode_to_vec(&c.cache, bincode::config::standard()).unwrap();
                assert!(state(&cpu) == state(&reference), "round {round}: cache state (LRU) differs");
                assert_eq!(cpu.cache.fetch_memo_line_ptr().cast::<u32>().is_null(), false);
            }
        }
    }
}
