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

use super::a64::{Asm, Cond, LR, R, SP, Shift, Size::W, Size::X, ZR};
use super::a64_translate::{RegCache, REGS, TState, T_REG, translate, translate_cached};
use super::form::{Block, LineRef};
use super::{
    Exit, F_FETCH, F_NOTE, F_SLOT, K_BRANCH, K_CALLOUT, K_DELAYED, K_NATIVE, LINK_NONE, Uop,
};
use crate::bus::{AccessContext, BusInterface};
use crate::registers::{Sh2Registers, StatusRegister};
use crate::Sh2;
use std::mem::offset_of;

/// Addresses of shared out-of-line stubs at the head of each code chunk
#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct Stubs {
    #[allow(dead_code)]
    pub(crate) enter: usize,
    pub(crate) exit: usize,
    pub(crate) fetch_hit: usize,
    pub(crate) read: [usize; 3],
    pub(crate) read_r0_enabled: [usize; 3],
    pub(crate) read_r0_on: [usize; 3],
    pub(crate) write: [usize; 3],
    pub(crate) write_r0_enabled: [usize; 3],
    pub(crate) dispatch_pc_clear: usize,
    pub(crate) chain_static_keep: usize,
    pub(crate) chain_static_clear: usize,
}

pub(crate) const FAST_SLOTS: usize = 512;

#[repr(C)]
#[derive(Clone, Copy)]
pub(crate) struct FastSlot {
    pub(crate) pc: u32,
    pub(crate) seen: u32,
    pub(crate) slot: u16,
    pub(crate) n: u8,
    pub(crate) _pad: [u8; 5],
    pub(crate) entry: usize,
    pub(crate) block: usize,
}
const _: () = assert!(std::mem::size_of::<FastSlot>() == 32);
const _: () = assert!(offset_of!(FastSlot, block) == offset_of!(FastSlot, entry) + 8);

impl Default for FastSlot {
    fn default() -> Self {
        Self { pc: u32::MAX, seen: 0, slot: 0, n: 0, _pad: [0; 5], entry: 0, block: 0 }
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub(crate) struct JitRet {
    pub(crate) packed: u64,
    pub(crate) counter: u64,
}

#[repr(C)]
pub(crate) struct Ctx {
    regs: *mut Sh2Registers,
    pub(crate) cpu: *mut Sh2,
    counter: u64,
    block: *const Block,
    /// Instructions executed by blocks left through native chaining
    total: u64,
    bus: *mut (),
    tramp: usize,
    /// Optional 256 KiB native-u16 SDRAM backing pointer (`null` when unavailable)
    sdram: *mut u16,
    /// Fast direct-mapped L1d dispatch table (`FAST_SLOTS` * 32B) for dynamic PC / entry lookup
    fast_table: *const FastSlot,
    /// Optional flat big-endian Cartridge ROM pointer (`null` when unavailable/RV=1/cheated)
    rom_ptr: *const u16,
    read_slow: usize,
    write_slow: usize,
    n: u64,
    stopped: u64,
    /// Byte length of the flat Cartridge ROM slice (<= 4 MiB)
    rom_len: u32,
    write_val: u32,
    /// Watched idle loop head for the active run (`NO_WATCH` when skipping is impossible)
    pub(crate) watch: u32,
    /// Packed `(or_mask << 8) | and_mask` pseudo-LRU update table indexed by `way` (0..=3)
    lru_masks: [u16; 4],
}

// SAFETY: `Ctx` belongs exclusively to the owning `JitCache`/`Sh2` and is only accessed via `&mut`;
// cloned/deserialized `Sh2` instances start with a fresh `Ctx` via `JitCache::default()`.
unsafe impl Send for Ctx {}
unsafe impl Sync for Ctx {}

impl Default for Ctx {
    fn default() -> Self {
        Self {
            regs: std::ptr::null_mut(),
            cpu: std::ptr::null_mut(),
            counter: 0,
            block: std::ptr::null(),
            total: 0,
            bus: std::ptr::null_mut(),
            tramp: 0,
            sdram: std::ptr::null_mut(),
            fast_table: std::ptr::null(),
            rom_ptr: std::ptr::null(),
            read_slow: 0,
            write_slow: 0,
            n: 0,
            stopped: 0,
            rom_len: 0,
            write_val: 0,
            watch: crate::idle::NO_WATCH,
            lru_masks: [0x00C7, 0x20F9, 0x14FE, 0x0BFF],
        }
    }
}

impl Ctx {
    #[inline(always)]
    pub(crate) fn invalidate(&mut self) {
        self.cpu = std::ptr::null_mut();
    }

    #[cold]
    #[inline(never)]
    pub(crate) fn init_static<Bus: BusInterface>(&mut self, cpu: *mut Sh2, fast_table: *const FastSlot) {
        // SAFETY: `cpu` is the owning `Sh2` for this `JitCache`.
        unsafe {
            self.regs = &raw mut (*cpu).registers;
            self.cpu = cpu;
            self.tramp = tramp::<Bus> as *const () as usize;
            self.read_slow = read_slow::<Bus> as *const () as usize;
            self.write_slow = write_slow::<Bus> as *const () as usize;
            self.fast_table = fast_table;
            self.watch = (*cpu).idle_watch.0.head;
        }
    }

    /// # Safety
    /// Must only be used during the synchronous `jit_run` borrow of `bus`.
    #[inline(always)]
    pub(crate) unsafe fn set_bus<Bus: BusInterface>(&mut self, bus: *mut Bus) {
        unsafe {
            let (sdram, rom_ptr, rom_len) = (*bus).native_memory_ptrs();
            self.bus = bus.cast();
            self.sdram = sdram;
            self.rom_ptr = rom_ptr;
            self.rom_len = rom_len;
        }
    }
}

const CTX_REGS: u32 = offset_of!(Ctx, regs) as u32;
const CTX_COUNTER: u32 = offset_of!(Ctx, counter) as u32;
const CTX_TRAMP: u32 = offset_of!(Ctx, tramp) as u32;
const CTX_CPU: u32 = offset_of!(Ctx, cpu) as u32;
const CTX_BLOCK: u32 = offset_of!(Ctx, block) as u32;
const CTX_N: u32 = offset_of!(Ctx, n) as u32;
const CTX_TOTAL: u32 = offset_of!(Ctx, total) as u32;
const _: () = assert!(CTX_CPU == CTX_REGS + 8);
const _: () = assert!(CTX_TOTAL == CTX_BLOCK + 8);
#[allow(dead_code)]
const CTX_SDRAM: u32 = offset_of!(Ctx, sdram) as u32;
#[allow(dead_code)]
const CTX_FAST_TABLE: u32 = offset_of!(Ctx, fast_table) as u32;
#[allow(dead_code)]
const CTX_ROM_PTR: u32 = offset_of!(Ctx, rom_ptr) as u32;
#[allow(dead_code)]
const CTX_ROM_LEN: u32 = offset_of!(Ctx, rom_len) as u32;
#[allow(dead_code)]
const CTX_WRITE_VAL: u32 = offset_of!(Ctx, write_val) as u32;
#[allow(dead_code)]
const CTX_READ_SLOW: u32 = offset_of!(Ctx, read_slow) as u32;
#[allow(dead_code)]
const CTX_WRITE_SLOW: u32 = offset_of!(Ctx, write_slow) as u32;
#[allow(dead_code)]
const CTX_LRU_MASKS: u32 = offset_of!(Ctx, lru_masks) as u32;
#[allow(dead_code)]
const CTX_WATCH: u32 = offset_of!(Ctx, watch) as u32;
const DATA_CTX_OFF: u32 = offset_of!(Sh2, data_ctx) as u32;
const _: () = assert!(CTX_LRU_MASKS < 4096);
const _: () = assert!(CTX_WATCH < 4096);
const _: () = assert!(DATA_CTX_OFF + 8 < 4096);
const _: () = {
    let layout = crate::cache::CpuCache::native_layout();
    let cache = offset_of!(Sh2, cache);
    assert!(cache + layout.lru < 4096);
    assert!(cache + layout.entry_tags < 4096);
    assert!(cache + layout.enabled < 4096);
    assert!(cache + layout.gens_conf < 4096);
    assert!(cache + layout.gens_code < 4096);
    assert!(cache + layout.gens_lines < 4096);
    assert!(std::mem::size_of::<super::Seen>() == 4);
};
const CPU: R = R(24);
const TICKS: R = R(25);
const TOTAL: R = R(26);
const BLOCK: R = R(27);

// Trampoline requests
const T_CALLOUT: u64 = 0;
const T_FETCH: u64 = 1;
const T_NOTE: u64 = 2;

// Block exit statuses (high half of the return value; low half = micro-ops executed)
const S_END: u64 = 0;
const S_TAKEN: u64 = 1;
const S_STOP: u64 = 2;
#[allow(dead_code)]
const S_DEOPT: u64 = 3;
const S_CEXIT: u64 = 4;
const S_INVAL: u64 = 5;

const CTX: R = R(20);
const COUNTER: R = R(21);
const LIMIT: R = R(22);
const MEMO: R = R(23);
const T0: R = R(9);
const T1: R = R(10);

fn reg_off(field: usize) -> u32 {
    field as u32
}

fn watch_offset() -> u32 {
    (offset_of!(Sh2, idle_watch)
        + offset_of!(jgenesis_common::hostcache::HostCache<crate::idle::IdleWatch>, 0)
        + offset_of!(crate::idle::IdleWatch, head)) as u32
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
    let layout = crate::cache::CpuCache::native_layout();
    let memo_off = (offset_of!(Sh2, cache) + layout.memo_line) as u32;
    a.str(W, MEMO, CPU, memo_off);
    a.str(X, BLOCK, CTX, CTX_BLOCK);
    a.mov(X, R(0), CTX);
    let arg = (kind << 32) | index as u64;
    a.movz(X, R(1), arg as u16, 0);
    a.movk(X, R(1), (arg >> 32) as u16, 32);
    a.ldr(X, R(16), CTX, CTX_TRAMP);
    a.blr(R(16));
    a.ldr(W, MEMO, CPU, memo_off);
}

/// Hot inline guard of `F_NOTE`: jumps to `miss` only if `target` is neither watched nor rejected.
fn emit_f_note_check(a: &mut Asm, target: u32, skip: super::a64::Label, miss: super::a64::Label) {
    let rslot = crate::idle::RejectedFilter::slot(target);
    let rej_off = (offset_of!(Sh2, idle_rejected)
        + offset_of!(jgenesis_common::hostcache::HostCache<crate::idle::RejectedFilter>, 0)
        + offset_of!(crate::idle::RejectedFilter, 0)
        + 4 * rslot) as u32;
    a.ldr(W, T0, CPU, watch_offset());
    a.mov_imm32(T1, target);
    a.cmp(W, T0, T1);
    a.b_cond(Cond::Eq, skip);
    a.ldr(W, T0, CPU, rej_off);
    a.cmp(W, T0, T1);
    a.b_cond(Cond::Ne, miss);
}

fn emit_f_note(a: &mut Asm, target: u32, i: usize) {
    let skip = a.new_label();
    let miss = a.new_label();
    emit_f_note_check(a, target, skip, miss);
    a.b(skip);
    a.bind(miss);
    emit_tramp(a, T_NOTE, i);
    a.bind(skip);
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

/// Branchless pseudo-LRU update with runtime `entry_reg` and `way_reg` (`0..=3`).
/// Clobbers `R(15), R(16), R(17)`.
fn emit_update_lru_dyn(
    a: &mut Asm,
    layout: &crate::cache::NativeLayout,
    off: &dyn Fn(usize) -> u32,
    entry_reg: R,
    way_reg: R,
) {
    a.add_imm(X, R(16), CPU, off(layout.lru));
    a.add_imm(X, R(15), CTX, CTX_LRU_MASKS);
    a.ldrb_reg(R(17), R(16), entry_reg);
    a.ldrh_reg(R(15), R(15), way_reg, true);
    a.and(W, R(17), R(17), R(15));
    a.orr_shifted(W, R(17), R(17), R(15), Shift::Lsr, 8);
    a.strb_reg(R(17), R(16), entry_reg);
}

fn emit_invalidate_memo_entry(a: &mut Asm, entry_reg: R, scratch: R) {
    a.ubfm(W, scratch, MEMO, 4, 9);
    a.cmp(W, scratch, entry_reg);
    a.csinv(W, MEMO, MEMO, ZR, Cond::Ne);
}

/// The fetch memo check of micro-op `i`: checks `MEMO (w23)` inline (3 insns on hit) and calls
/// the shared `fetch_hit` stub on miss so way/entry/LRU updates in `Block::lines` apply cleanly.
fn emit_fetch_check(a: &mut Asm, block: &Block, i: usize, stubs: &Stubs) {
    let line_idx = usize::from(block.ops[i].line);
    let line = &block.lines[line_idx];
    let skip = a.new_label();
    a.mov_imm32(T1, line.addr);
    a.cmp(W, MEMO, T1);
    a.b_cond(Cond::Eq, skip);
    a.movz(W, R(11), line_idx as u16, 0);
    a.bl_abs_deferred(stubs.fetch_hit);
    a.bind(skip);
}

#[derive(Clone, Copy)]
enum AddrBase {
    Reg(usize),
    Indexed(usize),
    Gbr,
    Abs,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum RegTarget {
    Gpr(usize),
    Pr,
    Mach,
    Macl,
    Gbr,
    Vbr,
}

impl RegTarget {
    fn reg_offset(self) -> u32 {
        reg_off(match self {
            Self::Gpr(n) => offset_of!(Sh2Registers, gpr) + 4 * n,
            Self::Pr => offset_of!(Sh2Registers, pr),
            Self::Mach => offset_of!(Sh2Registers, mach),
            Self::Macl => offset_of!(Sh2Registers, macl),
            Self::Gbr => offset_of!(Sh2Registers, gbr),
            Self::Vbr => offset_of!(Sh2Registers, vbr),
        })
    }
}

/// A load the compiler inlines: address source, size in bytes, optional post-increment, destination
#[derive(Clone, Copy)]
pub(crate) struct InlineRead {
    base: AddrBase,
    disp: u32,
    size: u32,
    dest: RegTarget,
    post_inc: bool,
    extra_cycles: u32,
}

/// A store the compiler inlines: address source, size in bytes, optional pre-decrement, source
#[derive(Clone, Copy)]
pub(crate) struct InlineWrite {
    base: AddrBase,
    disp: u32,
    size: u32,
    src: RegTarget,
    pre_dec: bool,
    extra_cycles: u32,
}

/// Loads whose interpreter handler is `dest = read(address)` (plus optional `Rm += size`) and whose
/// address does not depend on PC at run time
pub(crate) fn inline_read(op: &Uop) -> Option<InlineRead> {
    let o = op.opcode;
    let n = rn(o);
    let m = usize::from((o >> 4) & 0xF);
    if op.flags & F_SLOT != 0 && matches!(o >> 12, 0x9 | 0xD) {
        return None;
    }
    let next_pc = op.addr.wrapping_add(4);
    Some(match o >> 12 {
        0x6 if o & 0xF <= 2 => InlineRead { base: AddrBase::Reg(m), disp: 0, size: 1 << (o & 3), dest: RegTarget::Gpr(n), post_inc: false, extra_cycles: 0 },
        0x6 if (4..=6).contains(&(o & 0xF)) => InlineRead { base: AddrBase::Reg(m), disp: 0, size: 1 << ((o & 3) as u32), dest: RegTarget::Gpr(n), post_inc: true, extra_cycles: 0 },
        0x0 if (0xC..=0xE).contains(&(o & 0xF)) => InlineRead { base: AddrBase::Indexed(m), disp: 0, size: 1 << ((o & 3) as u32), dest: RegTarget::Gpr(n), post_inc: false, extra_cycles: 0 },
        0x5 => InlineRead { base: AddrBase::Reg(m), disp: u32::from(o & 0xF) << 2, size: 4, dest: RegTarget::Gpr(n), post_inc: false, extra_cycles: 0 },
        0x8 if (o >> 8) & 0xF == 4 => InlineRead { base: AddrBase::Reg(m), disp: u32::from(o & 0xF), size: 1, dest: RegTarget::Gpr(0), post_inc: false, extra_cycles: 0 },
        0x8 if (o >> 8) & 0xF == 5 => InlineRead { base: AddrBase::Reg(m), disp: u32::from(o & 0xF) << 1, size: 2, dest: RegTarget::Gpr(0), post_inc: false, extra_cycles: 0 },
        0xC if (4..=6).contains(&((o >> 8) & 0xF)) => {
            let sz_shift = ((o >> 8) & 0xF) - 4;
            InlineRead { base: AddrBase::Gbr, disp: u32::from(o & 0xFF) << sz_shift, size: 1 << sz_shift, dest: RegTarget::Gpr(0), post_inc: false, extra_cycles: 0 }
        }
        0xD => InlineRead { base: AddrBase::Abs, disp: (next_pc & !3).wrapping_add(u32::from(o & 0xFF) << 2), size: 4, dest: RegTarget::Gpr(n), post_inc: false, extra_cycles: 0 },
        0x9 => InlineRead { base: AddrBase::Abs, disp: next_pc.wrapping_add(u32::from(o & 0xFF) << 1), size: 2, dest: RegTarget::Gpr(n), post_inc: false, extra_cycles: 0 },
        0x4 => match o & 0xF0FF {
            0x4006 => InlineRead { base: AddrBase::Reg(n), disp: 0, size: 4, dest: RegTarget::Mach, post_inc: true, extra_cycles: 0 },
            0x4016 => InlineRead { base: AddrBase::Reg(n), disp: 0, size: 4, dest: RegTarget::Macl, post_inc: true, extra_cycles: 0 },
            0x4026 => InlineRead { base: AddrBase::Reg(n), disp: 0, size: 4, dest: RegTarget::Pr, post_inc: true, extra_cycles: 0 },
            0x4017 => InlineRead { base: AddrBase::Reg(n), disp: 0, size: 4, dest: RegTarget::Gbr, post_inc: true, extra_cycles: 2 },
            0x4027 => InlineRead { base: AddrBase::Reg(n), disp: 0, size: 4, dest: RegTarget::Vbr, post_inc: true, extra_cycles: 2 },
            _ => return None,
        },
        _ => return None,
    })
}

/// Stores whose interpreter handler is `write(address, src)` (plus optional `Rn -= size`)
pub(crate) fn inline_write(op: &Uop) -> Option<InlineWrite> {
    let o = op.opcode;
    let n = rn(o);
    let m = usize::from((o >> 4) & 0xF);
    Some(match o >> 12 {
        0x2 if o & 0xF <= 2 => InlineWrite { base: AddrBase::Reg(n), disp: 0, size: 1 << (o & 3), src: RegTarget::Gpr(m), pre_dec: false, extra_cycles: 0 },
        0x2 if (4..=6).contains(&(o & 0xF)) => InlineWrite { base: AddrBase::Reg(n), disp: 0, size: 1 << ((o & 3) as u32), src: RegTarget::Gpr(m), pre_dec: true, extra_cycles: 0 },
        0x0 if (4..=6).contains(&(o & 0xF)) => InlineWrite { base: AddrBase::Indexed(n), disp: 0, size: 1 << ((o & 3) as u32), src: RegTarget::Gpr(m), pre_dec: false, extra_cycles: 0 },
        0x1 => InlineWrite { base: AddrBase::Reg(n), disp: u32::from(o & 0xF) << 2, size: 4, src: RegTarget::Gpr(m), pre_dec: false, extra_cycles: 0 },
        // Notice 0x80/0x81 encode destination base Rn in bits 4..7 (`m` nibble) and R0 as src
        0x8 if (o >> 8) & 0xF == 0 => InlineWrite { base: AddrBase::Reg(m), disp: u32::from(o & 0xF), size: 1, src: RegTarget::Gpr(0), pre_dec: false, extra_cycles: 0 },
        0x8 if (o >> 8) & 0xF == 1 => InlineWrite { base: AddrBase::Reg(m), disp: u32::from(o & 0xF) << 1, size: 2, src: RegTarget::Gpr(0), pre_dec: false, extra_cycles: 0 },
        0xC if ((o >> 8) & 0xF) <= 2 => {
            let sz_shift = (o >> 8) & 0xF;
            InlineWrite { base: AddrBase::Gbr, disp: u32::from(o & 0xFF) << sz_shift, size: 1 << sz_shift, src: RegTarget::Gpr(0), pre_dec: false, extra_cycles: 0 }
        }
        0x4 => match o & 0xF0FF {
            0x4002 => InlineWrite { base: AddrBase::Reg(n), disp: 0, size: 4, src: RegTarget::Mach, pre_dec: true, extra_cycles: 0 },
            0x4012 => InlineWrite { base: AddrBase::Reg(n), disp: 0, size: 4, src: RegTarget::Macl, pre_dec: true, extra_cycles: 0 },
            0x4022 => InlineWrite { base: AddrBase::Reg(n), disp: 0, size: 4, src: RegTarget::Pr, pre_dec: true, extra_cycles: 0 },
            0x4013 => InlineWrite { base: AddrBase::Reg(n), disp: 0, size: 4, src: RegTarget::Gbr, pre_dec: true, extra_cycles: 1 },
            0x4023 => InlineWrite { base: AddrBase::Reg(n), disp: 0, size: 4, src: RegTarget::Vbr, pre_dec: true, extra_cycles: 1 },
            _ => return None,
        },
        _ => return None,
    })
}

#[cfg_attr(not(test), allow(dead_code))]
fn emit_addr(a: &mut Asm, base: AddrBase, disp: u32, pre_dec_size: u32) {
    match base {
        AddrBase::Reg(m) => {
            a.ldr(W, T0, REGS, gpr(m));
            if pre_dec_size != 0 {
                a.sub_imm(W, T0, T0, pre_dec_size);
            } else if disp != 0 {
                a.add_imm(W, T0, T0, disp);
            }
        }
        AddrBase::Indexed(m) => {
            a.ldr(W, T0, REGS, gpr(0));
            a.ldr(W, T1, REGS, gpr(m));
            a.add(W, T0, T0, T1);
        }
        AddrBase::Gbr => {
            a.ldr(W, T0, REGS, reg_off(offset_of!(Sh2Registers, gbr)));
            if disp != 0 {
                a.mov_imm32(T1, disp);
                a.add(W, T0, T0, T1);
            }
        }
        AddrBase::Abs => a.mov_imm32(T0, disp),
    }
}

fn emit_addr_rc(a: &mut Asm, rc: &mut RegCache, base: AddrBase, disp: u32, pre_dec_size: u32) {
    match base {
        AddrBase::Reg(m) => {
            let rm_r = rc.read(a, m);
            if pre_dec_size != 0 {
                a.sub_imm(W, T0, rm_r, pre_dec_size);
            } else if disp != 0 {
                a.add_imm(W, T0, rm_r, disp);
            } else {
                a.mov(W, T0, rm_r);
            }
        }
        AddrBase::Indexed(m) => {
            let r0 = rc.read(a, 0);
            let rm_r = if m == 0 { r0 } else { rc.read(a, m) };
            a.add(W, T0, r0, rm_r);
        }
        AddrBase::Gbr => {
            a.ldr(W, T0, REGS, reg_off(offset_of!(Sh2Registers, gbr)));
            if disp != 0 {
                a.mov_imm32(T1, disp);
                a.add(W, T0, T0, T1);
            }
        }
        AddrBase::Abs => a.mov_imm32(T0, disp),
    }
}

/// Computes `x11 = entry` (bits 4..9), `x12 = tag` (bits 10..28), probes ways 0..=3 branchlessly
/// via `ldp` in the inline `entry_tags` table and sets `w10` to `way` (`0..=3`) or
/// `0xFFFF_FFFF` (`bit 31 = 1`) on miss. Preserves `T0 (w9)`, `R(0)..=R(8)`, `R(13)`, `x19..x28`.
fn emit_cache_probe_branchless(
    a: &mut Asm,
    layout: &crate::cache::NativeLayout,
    off: &dyn Fn(usize) -> u32,
    load_ram_to: Option<R>,
) {
    a.ubfm(W, R(11), T0, 4, 9);
    a.add_imm(X, R(15), CPU, off(layout.entry_tags));
    a.add_shifted(X, R(15), R(15), R(11), Shift::Lsl, 4);
    a.ldp(R(14), R(16), R(15), 0);
    if let Some(ram_reg) = load_ram_to {
        a.ldr(X, ram_reg, CPU, off(layout.ram));
    }
    a.ubfm(W, R(12), T0, 10, 28);
    a.movz(W, R(17), 2, 0);
    a.cmp(W, R(14), R(12));
    a.csinv(W, R(10), ZR, ZR, Cond::Eq);
    a.lsr_imm(X, R(14), R(14), 32);
    a.cmp(W, R(14), R(12));
    a.csinc(W, R(10), R(10), ZR, Cond::Ne);
    a.cmp(W, R(16), R(12));
    a.csel(W, R(10), R(17), R(10), Cond::Eq);
    a.lsr_imm(X, R(16), R(16), 32);
    a.cmp(W, R(16), R(12));
    a.csinc(W, R(10), R(10), R(17), Cond::Ne);
}

fn emit_line_byte_offset_dyn(a: &mut Asm, off_reg: R, size: u32, way_reg: R, entry_reg: R) {
    let mask = match size {
        1 => 0xF,
        2 => 0xE,
        _ => 0xC,
    };
    assert!(a.and_imm(W, off_reg, T0, mask));
    if size == 1 {
        assert!(a.eor_imm(W, off_reg, off_reg, 1));
    }
    a.add_shifted(W, off_reg, off_reg, entry_reg, Shift::Lsl, 4);
    a.add_shifted(W, off_reg, off_reg, way_reg, Shift::Lsl, 10);
}

fn emit_sdram_byte_offset(a: &mut Asm, off_reg: R, size: u32, data_array: bool) {
    let mask = if data_array {
        match size {
            1 => 0xFFF,
            2 => 0xFFE,
            _ => 0xFFC,
        }
    } else {
        match size {
            1 => 0x3_FFFF,
            2 => 0x3_FFFE,
            _ => 0x3_FFFC,
        }
    };
    assert!(a.and_imm(W, off_reg, T0, mask));
    if size == 1 {
        assert!(a.eor_imm(W, off_reg, off_reg, 1));
    }
}

fn emit_mem_read(a: &mut Asm, base_reg: R, off_reg: R, dst: R, size: u32) {
    match size {
        1 => {
            a.ldrb_reg(dst, base_reg, off_reg);
            a.sxtb(W, dst, dst);
        }
        2 => {
            a.ldrh_reg(dst, base_reg, off_reg, false);
            a.sxth(W, dst, dst);
        }
        _ => {
            a.ldr_w_reg(dst, base_reg, off_reg, false);
            a.ror_imm(W, dst, dst, 16);
        }
    }
}

fn emit_mem_write(a: &mut Asm, base_reg: R, off_reg: R, val: R, tmp: R, size: u32) {
    match size {
        1 => a.strb_reg(val, base_reg, off_reg),
        2 => a.strh_reg(val, base_reg, off_reg, false),
        _ => {
            a.ror_imm(W, tmp, val, 16);
            a.str_w_reg(tmp, base_reg, off_reg, false);
        }
    }
}

fn emit_bump_cache_counters(a: &mut Asm, layout: &crate::cache::NativeLayout, off: &dyn Fn(usize) -> u32, line_idx_reg: R) {
    a.ldr(W, R(14), CPU, off(layout.epoch));
    a.add_imm(W, R(14), R(14), 1);
    a.str(W, R(14), CPU, off(layout.epoch));
    a.ldr(W, R(14), CPU, off(layout.gens_any));
    a.add_imm(W, R(14), R(14), 1);
    a.str(W, R(14), CPU, off(layout.gens_any));
    a.add_shifted(X, R(14), CPU, line_idx_reg, super::a64::Shift::Lsl, 2);
    a.ldr(W, R(15), R(14), off(layout.gens_lines));
    a.add_imm(W, R(15), R(15), 1);
    a.str(W, R(15), R(14), off(layout.gens_lines));
}

/// Emits all shared per-chunk stubs at `chunk_base` and returns their machine words + entry PCs.
#[cfg_attr(not(any(test, all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"), feature = "jit-arm64"))), allow(dead_code))]
pub(crate) fn build_stubs(chunk_base: usize) -> (Vec<u32>, Stubs) {
    let mut a = Asm::new();
    let enter = chunk_base + a.pos() * 4;
    emit_stub_enter(&mut a);
    let exit = chunk_base + a.pos() * 4;
    emit_stub_exit(&mut a);
    let fetch_hit = chunk_base + a.pos() * 4;
    emit_stub_fetch_hit(&mut a);

    let read_slow_common = a.new_label();
    let mut read = [0usize; 3];
    let mut read_r0_enabled = [0usize; 3];
    let mut read_r0_on = [0usize; 3];
    for (idx, &size) in [1u32, 2, 4].iter().enumerate() {
        read[idx] = chunk_base + a.pos() * 4;
        let (r0_en, r0_on) = emit_stub_read(&mut a, size, chunk_base, read_slow_common);
        read_r0_enabled[idx] = r0_en;
        read_r0_on[idx] = r0_on;
    }
    emit_read_slow_common(&mut a, read_slow_common);

    let write_slow_common = a.new_label();
    let mut write = [0usize; 3];
    let mut write_r0_enabled = [0usize; 3];
    for (idx, &size) in [1u32, 2, 4].iter().enumerate() {
        write[idx] = chunk_base + a.pos() * 4;
        write_r0_enabled[idx] = emit_stub_write(&mut a, size, chunk_base, write_slow_common);
    }
    emit_write_slow_common(&mut a, write_slow_common);

    let dispatch_keep_lbl = a.new_label();
    a.bind(dispatch_keep_lbl);
    emit_stub_dispatch_pc(&mut a);

    let dispatch_pc_clear = chunk_base + a.pos() * 4;
    emit_clear_delay_wrapper(&mut a, dispatch_keep_lbl);

    let chain_static_keep_lbl = a.new_label();
    let chain_static_keep = chunk_base + a.pos() * 4;
    a.bind(chain_static_keep_lbl);
    emit_stub_chain_static(&mut a, dispatch_keep_lbl);

    let chain_static_clear = chunk_base + a.pos() * 4;
    emit_clear_delay_wrapper(&mut a, chain_static_keep_lbl);

    (
        a.finish(),
        Stubs {
            enter,
            exit,
            fetch_hit,
            read,
            read_r0_enabled,
            read_r0_on,
            write,
            write_r0_enabled,
            dispatch_pc_clear,
            chain_static_keep,
            chain_static_clear,
        },
    )
}

fn emit_stub_enter(a: &mut Asm) {
    let layout = crate::cache::CpuCache::native_layout();
    let memo_off = (offset_of!(Sh2, cache) + layout.memo_line) as u32;
    a.stp_pre(R(27), LR, SP, -80);
    a.stp(R(19), R(20), SP, 16);
    a.stp(R(21), R(22), SP, 32);
    a.stp(R(23), R(24), SP, 48);
    a.stp(R(25), R(26), SP, 64);
    a.mov(X, CTX, R(0));
    a.ldp(REGS, CPU, CTX, CTX_REGS as i32);
    a.mov(X, COUNTER, R(2));
    a.mov(X, LIMIT, R(3));
    a.mov(X, BLOCK, R(4));
    a.mov(X, TICKS, R(5));
    a.ldr(W, MEMO, CPU, memo_off);
    a.movz(X, TOTAL, 0, 0);
    a.br(R(1));
}

fn emit_stub_exit(a: &mut Asm) {
    let layout = crate::cache::CpuCache::native_layout();
    let memo_off = (offset_of!(Sh2, cache) + layout.memo_line) as u32;
    a.add(X, R(0), R(0), TOTAL);
    a.mov(X, R(1), COUNTER);
    a.str(W, MEMO, CPU, memo_off);
    a.stp(BLOCK, TOTAL, CTX, CTX_BLOCK as i32);
    a.ldp(R(25), R(26), SP, 64);
    a.ldp(R(23), R(24), SP, 48);
    a.ldp(R(21), R(22), SP, 32);
    a.ldp(R(19), R(20), SP, 16);
    a.ldp_post(R(27), LR, SP, 80);
    a.ret();
}

/// Shared fetch-miss stub.
/// Input: `w11 = line_idx` in `BLOCK.lines`. Updates `MEMO (w23)` & LRU.
/// Preserves `R(0)..R(8)`, `T0 (R(9))`, and `x19..x28`.
fn emit_stub_fetch_hit(a: &mut Asm) {
    const _: () = assert!(std::mem::size_of::<LineRef>() == 16);
    let layout = crate::cache::CpuCache::native_layout();
    let cache = offset_of!(Sh2, cache);
    let off = |field: usize| (cache + field) as u32;
    let lines_off = offset_of!(Block, lines) as u32;
    let addr_off = lines_off + offset_of!(LineRef, addr) as u32;
    let ram_base_off = lines_off + offset_of!(LineRef, ram_base) as u32;
    let entry_off = lines_off + offset_of!(LineRef, entry) as u32;
    let lru_and_off = lines_off + offset_of!(LineRef, lru_and) as u32;
    let lru_or_off = lines_off + offset_of!(LineRef, lru_or) as u32;

    a.add_shifted(X, R(12), BLOCK, R(11), Shift::Lsl, 4);
    a.ldrb(R(11), R(12), entry_off);
    a.add_imm(X, R(16), CPU, off(layout.lru));
    a.ldr(W, MEMO, R(12), addr_off);
    a.ldrh(R(14), R(12), ram_base_off);
    a.ldrb(R(15), R(12), lru_and_off);
    a.ldrb(R(17), R(12), lru_or_off);
    a.strh(R(14), CPU, off(layout.memo_ram_base));
    a.ldrb_reg(R(14), R(16), R(11));
    a.and(W, R(14), R(14), R(15));
    a.orr(W, R(14), R(14), R(17));
    a.strb_reg(R(14), R(16), R(11));
    a.ret();
}

/// Shared read fast-path stub for access size `size` (1, 2, or 4 bytes).
/// Returns `(read_r0_enabled, read_r0_on)`: `read_r0_enabled` assumes `cache.enabled` is true
/// (Region 0 blocks); `read_r0_on` additionally assumes `region == 0` (`AddrBase::Abs` loads).
/// Inputs: `w0 (R(0)) = uop_idx`, `w9 (T0) = guest address`.
/// Output: `w10 (T1) = 0` (ok) | `1` (test-only null slow fallback) | `2` (ok, block invalidated),
/// `w15 = sign-extended value`, updated `COUNTER`. Preserves `w9 (T0)`, `R(1)..=R(8)`, `x19..x28`, `LR`.
fn emit_stub_read(
    a: &mut Asm,
    size: u32,
    chunk_base: usize,
    slow_common: super::a64::Label,
) -> (usize, usize) {
    let layout = crate::cache::CpuCache::native_layout();
    let cache = offset_of!(Sh2, cache);
    let off = |field: usize| (cache + field) as u32;
    let (addr, x10, x11, x15, x16, x17) = (T0, T1, R(11), R(15), R(16), R(17));
    let size_idx: u16 = match size {
        1 => 0,
        2 => 1,
        _ => 2,
    };

    let r0_on_lbl = a.new_label();
    let slow = a.new_label();
    let non_region0 = a.new_label();
    let region0_uncached = a.new_label();
    let data_array = a.new_label();
    let sdram_read = a.new_label();
    let rom_read = a.new_label();
    let one_cyc = a.new_label();
    let sdram_cyc = a.new_label();

    // Generic entry (Region 6 blocks / tests): check region == 0 and `cache.enabled`.
    a.lsr_imm(W, x10, addr, 29);
    a.cbnz(W, x10, non_region0);
    a.ldrb(x10, CPU, off(layout.enabled));
    a.cbz(W, x10, region0_uncached);
    a.b(r0_on_lbl);

    // Hot Region 0 block entry: `cache.enabled` is guaranteed true, so region 0 falls straight through.
    let r0_enabled = chunk_base + a.pos() * 4;
    a.lsr_imm(W, x10, addr, 29);
    a.cbnz(W, x10, non_region0);

    a.bind(r0_on_lbl);
    let r0_on = chunk_base + a.pos() * 4;
    emit_cache_probe_branchless(a, &layout, &off, Some(R(15)));
    a.tbnz(x10, 31, slow);

    // `R(15)` already holds `cache.ram` (loaded during tag probe); interleave remaining loads
    emit_line_byte_offset_dyn(a, x17, size, x10, x11);
    a.add_imm(X, R(14), CPU, off(layout.lru));
    a.ldrb_reg(R(12), R(14), x11);
    a.add_imm(X, R(13), CTX, CTX_LRU_MASKS);
    a.ldrh_reg(R(13), R(13), x10, true);
    emit_mem_read(a, R(15), x17, R(15), size);
    emit_invalidate_memo_entry(a, x11, x16);
    a.and(W, R(12), R(12), R(13));
    a.orr_shifted(W, R(12), R(12), R(13), Shift::Lsr, 8);
    a.strb_reg(R(12), R(14), x11);

    a.bind(one_cyc);
    a.add_imm(X, COUNTER, COUNTER, 1);
    a.movz(W, x10, 0, 0);
    a.ret();

    a.bind(non_region0);
    a.cmp_imm(W, x10, 6);
    a.b_cond(Cond::Eq, data_array);
    a.cmp_imm(W, x10, 1);
    a.b_cond(Cond::Ne, slow);
    // Uncached SDRAM: 0x2600_0000..=0x2603_FFFF
    a.lsr_imm(W, x11, addr, 18);
    a.cmp_imm(W, x11, 0x980);
    a.b_cond(Cond::Eq, sdram_read);
    // Uncached Cartridge ROM: 0x2200_0000..=0x23FF_FFFF
    a.lsr_imm(W, x11, addr, 25);
    a.cmp_imm(W, x11, 0x11);
    a.b_cond(Cond::Eq, rom_read);
    a.b(slow);

    a.bind(region0_uncached);
    // Cache disabled: region 0 SDRAM is 0x0600_0000..=0x0603_FFFF, Cartridge is 0x0200_0000..=0x03FF_FFFF
    a.lsr_imm(W, x11, addr, 18);
    a.cmp_imm(W, x11, 0x180);
    a.b_cond(Cond::Eq, sdram_read);
    a.lsr_imm(W, x11, addr, 25);
    a.cmp_imm(W, x11, 1);
    a.b_cond(Cond::Eq, rom_read);
    a.b(slow);

    a.bind(data_array);
    a.ldr(X, x16, CPU, off(layout.ram));
    emit_sdram_byte_offset(a, x17, size, true);
    emit_mem_read(a, x16, x17, x15, size);
    a.b(one_cyc);

    a.bind(sdram_read);
    a.ldr(X, x16, CTX, CTX_SDRAM);
    a.cbz(X, x16, slow);
    emit_sdram_byte_offset(a, x17, size, false);
    emit_mem_read(a, x16, x17, x15, size);
    a.b(sdram_cyc);

    a.bind(rom_read);
    a.ldr(X, x16, CTX, CTX_ROM_PTR);
    a.cbz(X, x16, slow);
    a.ldr(W, R(12), CTX, CTX_ROM_LEN);
    match size {
        1 => {
            assert!(a.and_imm(W, x17, addr, 0x3F_FFFF));
            a.cmp(W, x17, R(12));
            a.b_cond(Cond::Hs, slow);
            assert!(a.eor_imm(W, x17, x17, 1));
        }
        2 => {
            assert!(a.and_imm(W, x17, addr, 0x3F_FFFE));
            a.add_imm(W, x11, x17, 2);
            a.cmp(W, x11, R(12));
            a.b_cond(Cond::Hi, slow);
        }
        _ => {
            assert!(a.and_imm(W, x17, addr, 0x3F_FFFC));
            a.add_imm(W, x11, x17, 4);
            a.cmp(W, x11, R(12));
            a.b_cond(Cond::Hi, slow);
        }
    }
    emit_mem_read(a, x16, x17, x15, size);
    a.add_imm(X, COUNTER, COUNTER, if size == 4 { 17 } else { 9 });
    a.movz(W, x10, 0, 0);
    a.ret();

    a.bind(sdram_cyc);
    a.add_imm(X, COUNTER, COUNTER, 11);
    a.movz(W, x10, 0, 0);
    a.ret();

    a.bind(slow);
    a.movz(W, R(12), size_idx, 0);
    a.b(slow_common);
    (r0_enabled, r0_on)
}

fn emit_read_slow_common(a: &mut Asm, read_slow_common: super::a64::Label) {
    let layout = crate::cache::CpuCache::native_layout();
    let cache = offset_of!(Sh2, cache);
    let off = |field: usize| (cache + field) as u32;
    let idle_slow = a.new_label();

    a.bind(read_slow_common);
    a.ldr(X, R(16), CTX, CTX_READ_SLOW);
    a.cbz(X, R(16), idle_slow);
    a.stp_pre(R(1), R(2), SP, -80);
    a.stp(R(3), R(4), SP, 16);
    a.stp(R(5), R(6), SP, 32);
    a.stp(R(7), R(8), SP, 48);
    a.stp(T0, LR, SP, 64);
    a.mov(W, R(1), T0);
    a.add_shifted(X, R(1), R(1), R(0), Shift::Lsl, 32);
    a.add_shifted(X, R(1), R(1), R(12), Shift::Lsl, 56);
    a.str(W, MEMO, CPU, off(layout.memo_line));
    a.str(X, COUNTER, CTX, CTX_COUNTER);
    a.str(X, BLOCK, CTX, CTX_BLOCK);
    a.mov(X, R(0), CTX);
    a.blr(R(16));
    a.ldr(W, MEMO, CPU, off(layout.memo_line));
    a.ldr(X, COUNTER, CTX, CTX_COUNTER);
    a.mov(W, R(15), R(0));
    a.lsr_imm(X, T1, R(0), 32);
    a.lsl_imm(W, T1, T1, 1);
    a.ldp(T0, LR, SP, 64);
    a.ldp(R(7), R(8), SP, 48);
    a.ldp(R(5), R(6), SP, 32);
    a.ldp(R(3), R(4), SP, 16);
    a.ldp_post(R(1), R(2), SP, 80);
    a.ret();

    a.bind(idle_slow);
    a.movz(W, T1, 1, 0);
    a.ret();
}

/// Shared write fast-path stub for access size `size` (1, 2, or 4 bytes).
/// Returns the `write_r0_enabled` entry PC assuming `cache.enabled` is invariant (Region 0 blocks).
/// Inputs: `w0 (R(0)) = value to write`, `w9 (T0) = guest address`, `w13 (R(13)) = uop_idx`.
/// Output: `w10 (T1) = 0` (ok) | `1` (test-only null slow fallback) | `2` (ok, block invalidated).
/// Preserves `w9 (T0)`, `R(1)..=R(8)`, `x19..x28`, `LR`.
fn emit_stub_write(
    a: &mut Asm,
    size: u32,
    chunk_base: usize,
    slow_common: super::a64::Label,
) -> usize {
    let layout = crate::cache::CpuCache::native_layout();
    let cache = offset_of!(Sh2, cache);
    let off = |field: usize| (cache + field) as u32;
    let (addr, val, x10, x11, x12, x14, x15, x16, x17) =
        (T0, R(0), T1, R(11), R(12), R(14), R(15), R(16), R(17));
    let size_idx: u16 = match size {
        1 => 0,
        2 => 1,
        _ => 2,
    };

    let slow = a.new_label();
    let region0_probe = a.new_label();
    let region0_uncached = a.new_label();
    let non_region0 = a.new_label();
    let data_array = a.new_label();
    let cache_miss = a.new_label();
    let unchanged = a.new_label();
    let sdram_write = a.new_label();
    let one_cyc = a.new_label();

    // Generic entry (Region 6 blocks / tests): check region == 0 and `cache.enabled`.
    a.lsr_imm(W, x10, addr, 29);
    a.cbnz(W, x10, non_region0);
    a.ldrb(x10, CPU, off(layout.enabled));
    a.cbz(W, x10, region0_uncached);
    a.b(region0_probe);

    // Hot Region 0 block entry: `cache.enabled` is guaranteed true; falls right through to SDRAM/cache check.
    let w_r0_enabled = chunk_base + a.pos() * 4;
    a.lsr_imm(W, x10, addr, 29);
    a.cbnz(W, x10, non_region0);

    // Region 0 SDRAM write with cache enabled: 0x0600_0000..=0x0603_FFFF
    a.bind(region0_probe);
    a.lsr_imm(W, x11, addr, 18);
    a.cmp_imm(W, x11, 0x180);
    a.b_cond(Cond::Ne, slow);
    a.ldr(X, x16, CTX, CTX_SDRAM);
    a.cbz(X, x16, slow);

    emit_cache_probe_branchless(a, &layout, &off, None);
    a.tbnz(x10, 31, cache_miss);

    // Unified cache-hit body with `x10 = way`, `x11 = entry`
    // Route via `write_slow` BEFORE mutating cache/SDRAM if writing into a compiled code line
    a.add_shifted(X, x14, CPU, x10, Shift::Lsl, 3);
    a.ldr(X, x14, x14, off(layout.gens_code_lines));
    a.lsrv(X, x14, x14, x11);
    a.tbnz(x14, 0, slow);

    emit_invalidate_memo_entry(a, x11, x16);
    emit_update_lru_dyn(a, &layout, &off, x11, x10);
    emit_line_byte_offset_dyn(a, x17, size, x10, x11);
    a.ldr(X, x16, CPU, off(layout.ram));
    match size {
        1 => {
            a.ldrb_reg(x15, x16, x17);
            assert!(a.and_imm(W, x14, val, 0xFF));
            a.cmp(W, x15, x14);
            a.b_cond(Cond::Eq, unchanged);
            a.strb_reg(val, x16, x17);
        }
        2 => {
            a.ldrh_reg(x15, x16, x17, false);
            a.uxth(x14, val);
            a.cmp(W, x15, x14);
            a.b_cond(Cond::Eq, unchanged);
            a.strh_reg(val, x16, x17, false);
        }
        _ => {
            a.ldr_w_reg(x15, x16, x17, false);
            a.ror_imm(W, x14, val, 16);
            a.cmp(W, x15, x14);
            a.b_cond(Cond::Eq, unchanged);
            a.str_w_reg(x14, x16, x17, false);
        }
    }
    a.add_shifted(W, x11, x11, x10, Shift::Lsl, 6);
    emit_bump_cache_counters(a, &layout, &off, x11);
    a.bind(unchanged);
    a.ldr(X, x16, CTX, CTX_SDRAM);
    a.b(sdram_write);

    a.bind(region0_uncached);
    a.lsr_imm(W, x11, addr, 18);
    a.cmp_imm(W, x11, 0x180);
    a.b_cond(Cond::Ne, slow);
    a.ldr(X, x16, CTX, CTX_SDRAM);
    a.cbz(X, x16, slow);
    a.b(sdram_write);

    a.bind(non_region0);
    a.cmp_imm(W, x10, 6);
    a.b_cond(Cond::Eq, data_array);
    a.cmp_imm(W, x10, 1);
    a.b_cond(Cond::Ne, slow);
    // Uncached SDRAM: 0x2600_0000..=0x2603_FFFF
    a.lsr_imm(W, x11, addr, 18);
    a.cmp_imm(W, x11, 0x980);
    a.b_cond(Cond::Ne, slow);
    a.ldr(X, x16, CTX, CTX_SDRAM);
    a.cbz(X, x16, slow);
    a.b(sdram_write);

    // Region 6: Cache-as-RAM
    a.bind(data_array);
    a.lsr_imm(W, x11, addr, 4);
    assert!(a.and_imm(W, x11, x11, 63));
    a.lsr_imm(W, x12, addr, 10);
    assert!(a.and_imm(W, x12, x12, 3));
    a.add_shifted(X, x14, CPU, x12, Shift::Lsl, 3);
    a.ldr(X, x14, x14, off(layout.gens_code_lines));
    a.lsrv(X, x14, x14, x11);
    a.tbnz(x14, 0, slow);
    a.ldr(X, x16, CPU, off(layout.ram));
    emit_sdram_byte_offset(a, x17, size, true);
    emit_mem_write(a, x16, x17, val, x15, size);
    a.add_shifted(W, x11, x11, x12, Shift::Lsl, 6);
    emit_bump_cache_counters(a, &layout, &off, x11);
    a.b(one_cyc);

    a.bind(cache_miss);
    emit_invalidate_memo_entry(a, x11, x16);
    a.ldr(X, x16, CTX, CTX_SDRAM);

    a.bind(sdram_write);
    emit_sdram_byte_offset(a, x17, size, false);
    emit_mem_write(a, x16, x17, val, x15, size);
    a.add_imm(X, COUNTER, COUNTER, 2);
    a.movz(W, x10, 0, 0);
    a.ret();

    a.bind(one_cyc);
    a.add_imm(X, COUNTER, COUNTER, 1);
    a.movz(W, x10, 0, 0);
    a.ret();

    a.bind(slow);
    a.movz(W, R(12), size_idx, 0);
    a.b(slow_common);
    w_r0_enabled
}

fn emit_write_slow_common(a: &mut Asm, write_slow_common: super::a64::Label) {
    let layout = crate::cache::CpuCache::native_layout();
    let cache = offset_of!(Sh2, cache);
    let off = |field: usize| (cache + field) as u32;
    let idle_slow = a.new_label();

    a.bind(write_slow_common);
    a.ldr(X, R(16), CTX, CTX_WRITE_SLOW);
    a.cbz(X, R(16), idle_slow);
    a.stp_pre(R(1), R(2), SP, -80);
    a.stp(R(3), R(4), SP, 16);
    a.stp(R(5), R(6), SP, 32);
    a.stp(R(7), R(8), SP, 48);
    a.stp(T0, LR, SP, 64);
    a.str(W, R(0), CTX, CTX_WRITE_VAL);
    a.mov(W, R(1), T0);
    a.add_shifted(X, R(1), R(1), R(13), Shift::Lsl, 32);
    a.add_shifted(X, R(1), R(1), R(12), Shift::Lsl, 56);
    a.str(W, MEMO, CPU, off(layout.memo_line));
    a.str(X, COUNTER, CTX, CTX_COUNTER);
    a.str(X, BLOCK, CTX, CTX_BLOCK);
    a.mov(X, R(0), CTX);
    a.blr(R(16));
    a.ldr(W, MEMO, CPU, off(layout.memo_line));
    a.ldr(X, COUNTER, CTX, CTX_COUNTER);
    a.lsl_imm(W, T1, R(0), 1);
    a.ldp(T0, LR, SP, 64);
    a.ldp(R(7), R(8), SP, 48);
    a.ldp(R(5), R(6), SP, 32);
    a.ldp(R(3), R(4), SP, 16);
    a.ldp_post(R(1), R(2), SP, 80);
    a.ret();

    a.bind(idle_slow);
    a.movz(W, T1, 1, 0);
    a.ret();
}

/// Calls `inner` and, when chaining succeeds (`x16 != 0`), clears `next_op_in_delay_slot`.
/// Keeps `bl`/`ret` paired so hardware RAS stays balanced; preserves `x16` and clobbers `x9`.
fn emit_clear_delay_wrapper(a: &mut Asm, inner: super::a64::Label) {
    let skip = a.new_label();
    let delay_off = reg_off(offset_of!(Sh2Registers, next_op_in_delay_slot));
    a.mov(X, R(9), LR);
    a.bl(inner);
    a.cbz(X, R(16), skip);
    a.strb(ZR, REGS, delay_off);
    a.bind(skip);
    a.mov(X, LR, R(9));
    a.ret();
}

/// Shared dynamic-PC dispatch stub for `RTS`, `JMP`, `JSR`, `BRAF`, `BSRF` and unlinked chain fallbacks.
/// Inputs: `w15 = target_pc`, `w11 = executed` (`n` of the leaving block).
/// On hit: commits chaining state and returns `x16 = next.entry`. On miss: returns `x16 = 0`.
fn emit_stub_dispatch_pc(a: &mut Asm) {
    const _: () = assert!(std::mem::size_of::<FastSlot>() == 32);
    let layout = crate::cache::CpuCache::native_layout();
    let code_off = (offset_of!(Sh2, cache) + layout.gens_code) as u32;
    let miss = a.new_label();

    // fast_slot = ((pc >> 1) ^ (pc >> 10)) & (FAST_SLOTS - 1)
    a.lsr_imm(W, R(12), R(15), 1);
    a.lsr_imm(W, R(13), R(15), 10);
    a.eor(W, R(12), R(12), R(13));
    assert!(a.and_imm(W, R(12), R(12), (FAST_SLOTS - 1) as u64));
    a.ldr(X, R(13), CTX, CTX_FAST_TABLE);
    a.add_shifted(X, R(12), R(13), R(12), Shift::Lsl, 5);

    // Validate slot: seen == cache.gens.code, pc == target_pc, entry != 0
    a.ldr(W, R(14), CPU, code_off);
    a.ldr(W, R(13), R(12), offset_of!(FastSlot, seen) as u32);
    a.cmp(W, R(13), R(14));
    a.b_cond(Cond::Ne, miss);
    a.ldr(W, R(13), R(12), offset_of!(FastSlot, pc) as u32);
    a.cmp(W, R(13), R(15));
    a.b_cond(Cond::Ne, miss);
    a.ldp(R(16), R(17), R(12), offset_of!(FastSlot, entry) as i32);
    a.cbz(X, R(16), miss);

    // Budget and idle-watch checks matching static chaining
    a.ldrb(R(13), R(12), offset_of!(FastSlot, n) as u32);
    a.cmp(X, TICKS, R(13));
    a.b_cond(Cond::Lo, miss);
    a.ldr(W, R(14), CTX, CTX_WATCH);
    a.sub(W, R(14), R(14), R(15));
    a.add(W, R(10), R(13), R(13));
    a.cmp(W, R(14), R(10));
    a.b_cond(Cond::Lo, miss);

    // Commit and return target entry in x16 for monomorphic caller `br x16`
    a.sub(X, TICKS, TICKS, R(13));
    a.add(X, TOTAL, TOTAL, R(11));
    a.mov(X, BLOCK, R(17));
    a.ret();

    a.bind(miss);
    a.movz(X, R(16), 0, 0);
    a.ret();
}

/// Shared static block-chaining stub.
/// Inputs: `x12 = block.chain[k]`, `w15 = next_pc`, `w11 = executed`.
/// Returns `x16 = target.entry` on hit (with chaining state committed) or `x16 = 0` on miss.
/// When `seen != cache.gens.code`, revalidates the block's line generation counters inline before
/// falling back to `dispatch_fallback`.
fn emit_stub_chain_static(a: &mut Asm, dispatch_fallback: super::a64::Label) {
    let layout = crate::cache::CpuCache::native_layout();
    let cache = offset_of!(Sh2, cache);
    let code_off = (cache + layout.gens_code) as u32;
    let miss = a.new_label();
    let seen_ok = a.new_label();
    let revalidate_slow = a.new_label();
    let reval_loop = a.new_label();
    let reval_ok = a.new_label();

    a.cbz(X, R(12), dispatch_fallback);
    a.ldr(W, R(14), CPU, code_off);
    a.ldr(W, R(13), R(12), offset_of!(Block, seen) as u32);
    a.cmp(W, R(13), R(14));
    a.b_cond(Cond::Ne, revalidate_slow);
    a.bind(seen_ok);
    a.ldr(W, R(13), R(12), offset_of!(Block, start) as u32);
    a.cmp(W, R(13), R(15));
    a.b_cond(Cond::Ne, dispatch_fallback);
    a.ldr(X, R(16), R(12), offset_of!(Block, entry) as u32);
    a.cbz(X, R(16), dispatch_fallback);
    a.ldrb(R(13), R(12), offset_of!(Block, n) as u32);
    a.cmp(X, TICKS, R(13));
    a.b_cond(Cond::Lo, miss);
    a.ldr(W, R(14), CTX, CTX_WATCH);
    a.sub(W, R(14), R(14), R(15));
    a.add(W, R(10), R(13), R(13));
    a.cmp(W, R(14), R(10));
    a.b_cond(Cond::Lo, miss);

    a.sub(X, TICKS, TICKS, R(13));
    a.add(X, TOTAL, TOTAL, R(11));
    a.mov(X, BLOCK, R(12));
    a.ret();

    a.bind(revalidate_slow);
    a.ldr(W, R(13), R(12), offset_of!(Block, start) as u32);
    a.cmp(W, R(13), R(15));
    a.b_cond(Cond::Ne, dispatch_fallback);
    a.ldr(X, R(16), R(12), offset_of!(Block, entry) as u32);
    a.cbz(X, R(16), dispatch_fallback);
    a.ldr(W, R(13), R(12), offset_of!(Block, conf) as u32);
    a.ldr(W, R(14), CPU, (cache + layout.gens_conf) as u32);
    a.cmp(W, R(13), R(14));
    a.b_cond(Cond::Ne, dispatch_fallback);
    a.ldrb(R(10), R(12), offset_of!(Block, nlines) as u32);
    a.cbz(W, R(10), reval_ok);
    a.add_imm(X, R(16), R(12), offset_of!(Block, lines) as u32);
    a.add_imm(X, R(17), CPU, (cache + layout.gens_lines) as u32);
    a.bind(reval_loop);
    a.ldrb(R(13), R(16), offset_of!(LineRef, way) as u32);
    a.ldrb(R(14), R(16), offset_of!(LineRef, entry) as u32);
    a.add_shifted(W, R(13), R(14), R(13), Shift::Lsl, 6);
    a.ldr_w_reg(R(14), R(17), R(13), true);
    a.ldr(W, R(13), R(16), offset_of!(LineRef, gen_value) as u32);
    a.cmp(W, R(14), R(13));
    a.b_cond(Cond::Ne, dispatch_fallback);
    a.add_imm(X, R(16), R(16), std::mem::size_of::<LineRef>() as u32);
    a.sub_imm(W, R(10), R(10), 1);
    a.cbnz(W, R(10), reval_loop);
    a.bind(reval_ok);
    a.ldr(W, R(14), CPU, code_off);
    a.str(W, R(14), R(12), offset_of!(Block, seen) as u32);
    a.b(seen_ok);

    a.bind(miss);
    a.movz(X, R(16), 0, 0);
    a.ret();
}

type ExitFn<'a> = dyn FnMut(&mut Asm, usize, u64, u8) -> super::a64::Label + 'a;

/// Code of native micro-op `i` without its cycle charge; a taken `BT`/`BF` charges `taken_cost`
/// (the cycles not yet charged up to and including it) plus its extra and leaves the block
fn emit_native(
    a: &mut Asm,
    block: &Block,
    i: usize,
    region0: bool,
    taken_cost: u32,
    stubs: &Stubs,
    exit: &mut ExitFn<'_>,
) -> Option<()> {
    let op = &block.ops[i];
    if region0 && op.flags & F_FETCH != 0 {
        emit_fetch_check(a, block, i, stubs);
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
                emit_f_note(a, op.imm, i);
            }
            a.add_imm(X, COUNTER, COUNTER, taken_cost + u32::from(op.extra));
            let out = exit(a, i + 1, S_TAKEN, 1);
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
                        emit_f_note(a, op.imm, i);
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
                        emit_f_note(a, op.imm, i);
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

enum ColdTask {
    StopExit {
        snap: RegCache,
        label: super::a64::Label,
        out: super::a64::Label,
    },
    InvalExit {
        snap: RegCache,
        label: super::a64::Label,
        out: super::a64::Label,
    },
    TakenBranch {
        snap: RegCache,
        is_bt: bool,
        label: super::a64::Label,
        out: super::a64::Label,
        target: u32,
        i: usize,
        has_note: bool,
        add_cycles: u32,
    },
    FNote {
        snap: RegCache,
        cold: super::a64::Label,
        skip: super::a64::Label,
        i: usize,
    },
    FetchHit {
        label: super::a64::Label,
        ret_lbl: super::a64::Label,
        line_idx: usize,
    },
    CheckedSeg {
        s_in: RegCache,
        s_out: RegCache,
        checked: super::a64::Label,
        next: super::a64::Label,
        i: usize,
        end: usize,
    },
}

/// Hot fetch-memo check whose miss call (`fetch_hit` stub) lives in the cold section.
fn emit_fetch_check_cold(a: &mut Asm, block: &Block, i: usize, cold: &mut Vec<ColdTask>) {
    let line_idx = usize::from(block.ops[i].line);
    let line = &block.lines[line_idx];
    let miss = a.new_label();
    let ret = a.new_label();
    a.mov_imm32(T1, line.addr);
    a.cmp(W, MEMO, T1);
    a.b_cond(Cond::Ne, miss);
    a.bind(ret);
    cold.push(ColdTask::FetchHit { label: miss, ret_lbl: ret, line_idx });
}

/// Branches on `rc.t` (`true` = `BT`/`BT/S`, `false` = `BF`/`BF/S`) to `target_lbl`, fusing
/// directly with `TState::Flags` when live in NZCV.
fn emit_t_branch(a: &mut Asm, rc: &RegCache, on_true: bool, target_lbl: super::a64::Label) {
    match rc.t {
        TState::Flags(cond) => {
            let c = if on_true { cond } else { cond.invert() };
            a.b_cond(c, target_lbl);
        }
        TState::Reg { .. } => {
            if on_true {
                a.cbnz(W, T_REG, target_lbl);
            } else {
                a.cbz(W, T_REG, target_lbl);
            }
        }
        TState::Mem => {
            a.ldrb(T0, REGS, t_off());
            if on_true {
                a.cbnz(W, T0, target_lbl);
            } else {
                a.cbz(W, T0, target_lbl);
            }
        }
    }
}

/// Code of native micro-op `i` in the register-cached fast mainline; pushes cold/rare work to `cold`.
fn emit_native_rc(
    a: &mut Asm,
    rc: &mut RegCache,
    block: &Block,
    i: usize,
    region0: bool,
    taken_cost: u32,
    cold: &mut Vec<ColdTask>,
    exit: &mut ExitFn<'_>,
) -> Option<()> {
    let op = &block.ops[i];
    if region0 && op.flags & F_FETCH != 0 {
        rc.materialize_flags(a);
        emit_fetch_check_cold(a, block, i, cold);
    }

    match op.kind {
        K_NATIVE => {
            rc.unpin_all();
            if op.opcode >> 8 == 0xC7 {
                // MOVA: value resolved at formation time
                let r0 = rc.write_only(a, 0);
                a.mov_imm32(r0, op.imm);
            } else if !translate_cached(a, rc, op.opcode) {
                return None;
            }
        }
        K_BRANCH => {
            let is_bt = op.opcode >> 8 == 0x89;
            let taken_lbl = a.new_label();
            let out = exit(a, i + 1, S_TAKEN, 1);
            emit_t_branch(a, rc, is_bt, taken_lbl);
            cold.push(ColdTask::TakenBranch {
                snap: rc.clone(),
                is_bt,
                label: taken_lbl,
                out,
                target: op.imm,
                i,
                has_note: op.flags & F_NOTE != 0,
                add_cycles: taken_cost + u32::from(op.extra),
            });
        }
        K_DELAYED => {
            rc.unpin_all();
            let opcode = op.opcode;
            match opcode >> 12 {
                0xA | 0xB => {
                    if opcode >> 12 == 0xB {
                        emit_pr(a, op);
                    }
                    a.mov_imm32(T0, op.imm);
                    emit_delayed_target(a);
                    if op.flags & F_NOTE != 0 {
                        rc.materialize_flags(a);
                        let skip = a.new_label();
                        let cold_lbl = a.new_label();
                        emit_f_note_check(a, op.imm, skip, cold_lbl);
                        a.bind(skip);
                        cold.push(ColdTask::FNote { snap: rc.clone(), cold: cold_lbl, skip, i });
                    }
                }
                0x8 => {
                    // BT/S (0x8D), BF/S (0x8F)
                    let is_bt = opcode >> 8 == 0x8D;
                    if op.flags & F_NOTE != 0 {
                        rc.materialize_flags(a);
                    }
                    let untaken = a.new_label();
                    let join = a.new_label();
                    emit_t_branch(a, rc, !is_bt, untaken);
                    a.mov_imm32(T0, op.imm);
                    emit_delayed_target(a);
                    if op.flags & F_NOTE != 0 {
                        let skip = a.new_label();
                        let cold_lbl = a.new_label();
                        emit_f_note_check(a, op.imm, skip, cold_lbl);
                        a.bind(skip);
                        cold.push(ColdTask::FNote { snap: rc.clone(), cold: cold_lbl, skip, i });
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
                    let rn_r = rc.read(a, rn(opcode));
                    if opcode & 0xFF == 0x03 {
                        emit_pr(a, op);
                    }
                    a.mov_imm32(T1, op.imm);
                    a.add(W, T0, rn_r, T1);
                    emit_delayed_target(a);
                }
                _ => {
                    // JSR (0x0B) / JMP (0x2B)
                    if opcode & 0xFF == 0x0B {
                        emit_pr(a, op);
                    }
                    let rn_r = rc.read(a, rn(opcode));
                    a.str(W, rn_r, REGS, reg_off(offset_of!(Sh2Registers, next_pc)));
                    a.movz(W, T0, 1, 0);
                    a.strb(T0, REGS, reg_off(offset_of!(Sh2Registers, next_op_in_delay_slot)));
                }
            }
        }
        _ => unreachable!(),
    }

    Some(())
}

/// Machine code for `block` split into hot/cold regions, or `None` if it contains an
/// instruction the compiler does not translate (the block then stays in the threaded executor).
pub(crate) fn compile(block: &Block, stubs: &Stubs) -> Option<Asm> {
    let n = usize::from(block.n);
    let region0 = block.start >> 29 == 0;
    let mut a = Asm::new();
    let block_top = a.new_label();
    a.bind(block_top);
    // (label, return value: link << 40 | status << 32 | executed)
    let mut exits: Vec<(super::a64::Label, u64)> = Vec::new();
    let mut exit = |a: &mut Asm, executed: usize, status: u64, link: u8| {
        let label = a.new_label();
        exits.push((label, (u64::from(link) << 40) | (status << 32) | executed as u64));
        label
    };
    let mut cold: Vec<ColdTask> = Vec::new();
    let mut rc = RegCache::new();

    let mut i = 0;
    while i < n {
        let op = &block.ops[i];

        if op.kind == K_CALLOUT {
            let may_inval = op.flags & super::F_MEM != 0;
            if let Some(read) = inline_read(op) {
                rc.materialize_flags(&mut a);
                if region0 && op.flags & F_FETCH != 0 {
                    emit_fetch_check_cold(&mut a, block, i, &mut cold);
                }
                if matches!(read.base, AddrBase::Abs) && (read.disp >> 29) == 6 && !read.post_inc && read.extra_cycles == 0 {
                    rc.unpin_all();
                    let stop_lbl = a.new_label();
                    let layout = crate::cache::CpuCache::native_layout();
                    let ram_ptr_off = (offset_of!(Sh2, cache) + layout.ram) as u32;
                    let off_bytes = read.disp & if read.size == 4 { 0xFFC } else { 0xFFE };
                    a.ldr(X, R(16), CPU, ram_ptr_off);
                    let RegTarget::Gpr(d) = read.dest else { unreachable!() };
                    let dest_r = rc.write_only(&mut a, d);
                    if read.size == 4 {
                        a.ldr(W, dest_r, R(16), off_bytes);
                        a.ror_imm(W, dest_r, dest_r, 16);
                    } else {
                        a.ldrh(dest_r, R(16), off_bytes);
                        a.sxth(W, dest_r, dest_r);
                    }
                    a.add_imm(X, COUNTER, COUNTER, 1);
                    let s_out = rc.clone();
                    let out = exit(&mut a, i + 1, S_STOP, LINK_NONE);
                    a.cmp(X, COUNTER, LIMIT);
                    a.b_cond(Cond::Hs, stop_lbl);
                    cold.push(ColdTask::StopExit { snap: s_out, label: stop_lbl, out });
                    i += 1;
                    continue;
                }
                rc.unpin_all();
                emit_addr_rc(&mut a, &mut rc, read.base, read.disp, 0);
                let stop_lbl = a.new_label();
                a.movz(W, R(0), i as u16, 0);
                let size_idx = read.size as usize >> 1;
                let read_stub = if matches!(read.base, AddrBase::Abs) && region0 && (read.disp >> 29) == 0 {
                    stubs.read_r0_on[size_idx]
                } else if region0 {
                    stubs.read_r0_enabled[size_idx]
                } else {
                    stubs.read[size_idx]
                };
                a.bl_abs_deferred(read_stub);
                rc.unpin_all();
                if read.post_inc && let AddrBase::Reg(m) = read.base {
                    let dm = rc.write_only(&mut a, m);
                    a.add_imm(W, dm, T0, read.size);
                }
                match read.dest {
                    RegTarget::Gpr(d) => {
                        let dest_r = rc.write_only(&mut a, d);
                        a.mov(W, dest_r, R(15));
                    }
                    other => a.str(W, R(15), REGS, other.reg_offset()),
                }
                if read.extra_cycles != 0 {
                    a.add_imm(X, COUNTER, COUNTER, read.extra_cycles);
                }
                let s_out = rc.clone();
                let out = exit(&mut a, i + 1, S_STOP, LINK_NONE);
                a.cmp(X, COUNTER, LIMIT);
                a.b_cond(Cond::Hs, stop_lbl);
                if may_inval {
                    let inval_lbl = a.new_label();
                    let inval_out = exit(&mut a, i + 1, S_INVAL, LINK_NONE);
                    a.cbnz(W, T1, inval_lbl);
                    cold.push(ColdTask::InvalExit { snap: s_out.clone(), label: inval_lbl, out: inval_out });
                }
                cold.push(ColdTask::StopExit { snap: s_out, label: stop_lbl, out });
                i += 1;
                continue;
            } else if let Some(write) = inline_write(op) {
                debug_assert!(may_inval);
                rc.materialize_flags(&mut a);
                if region0 && op.flags & F_FETCH != 0 {
                    emit_fetch_check_cold(&mut a, block, i, &mut cold);
                }
                rc.unpin_all();
                match write.src {
                    RegTarget::Gpr(s) => {
                        let src_r = rc.read(&mut a, s);
                        a.mov(W, R(0), src_r);
                    }
                    other => a.ldr(W, R(0), REGS, other.reg_offset()),
                }
                rc.unpin_all();
                emit_addr_rc(&mut a, &mut rc, write.base, write.disp, if write.pre_dec { write.size } else { 0 });
                let stop_lbl = a.new_label();
                a.movz(W, R(13), i as u16, 0);
                let size_idx = write.size as usize >> 1;
                let write_stub = if region0 { stubs.write_r0_enabled[size_idx] } else { stubs.write[size_idx] };
                a.bl_abs_deferred(write_stub);
                if write.pre_dec && let AddrBase::Reg(n) = write.base {
                    rc.unpin_all();
                    let dn = rc.write_only(&mut a, n);
                    a.mov(W, dn, T0);
                }
                if write.extra_cycles != 0 {
                    a.add_imm(X, COUNTER, COUNTER, write.extra_cycles);
                }
                let s_out = rc.clone();
                let out = exit(&mut a, i + 1, S_STOP, LINK_NONE);
                a.cmp(X, COUNTER, LIMIT);
                a.b_cond(Cond::Hs, stop_lbl);
                if may_inval {
                    let inval_lbl = a.new_label();
                    let inval_out = exit(&mut a, i + 1, S_INVAL, LINK_NONE);
                    a.cbnz(W, T1, inval_lbl);
                    cold.push(ColdTask::InvalExit { snap: s_out.clone(), label: inval_lbl, out: inval_out });
                }
                cold.push(ColdTask::StopExit { snap: s_out, label: stop_lbl, out });
                i += 1;
                continue;
            }
            rc.flush_and_clear(&mut a);
            a.str(X, COUNTER, CTX, CTX_COUNTER);
            a.ldrb(T0, BLOCK, offset_of!(Block, n) as u32);
            a.str(X, T0, CTX, CTX_N);
            emit_tramp(&mut a, T_CALLOUT, i);
            a.ldr(X, COUNTER, CTX, CTX_COUNTER);
            let out = exit(&mut a, i + 1, S_CEXIT, LINK_NONE);
            a.cbnz(X, R(0), out);
            i += 1;
            continue;
        }

        // Native segment: hot straight-line path under static guard; slow checked copy in cold tail
        let end = usize::from(op.seg_end);
        let seg_cost: u32 = block.ops[i..end].iter().map(|o| u32::from(o.cost)).sum();
        if op.guard == 0 {
            emit_native_rc(&mut a, &mut rc, block, i, region0, seg_cost, &mut cold, &mut exit)?;
            rc.materialize_flags(&mut a);
            let s_out = rc.clone();
            let stop_lbl = a.new_label();
            let out = exit(&mut a, end, S_STOP, LINK_NONE);
            a.add_imm(X, COUNTER, COUNTER, seg_cost);
            a.cmp(X, COUNTER, LIMIT);
            a.b_cond(Cond::Hs, stop_lbl);
            cold.push(ColdTask::StopExit { snap: s_out, label: stop_lbl, out });
            i = end;
            continue;
        }
        rc.materialize_flags(&mut a);
        let s_in = rc.clone();
        let checked = a.new_label();
        let next = a.new_label();
        let stop_lbl = a.new_label();
        a.add_imm(X, T0, COUNTER, u32::from(op.guard));
        a.cmp(X, T0, LIMIT);
        a.b_cond(Cond::Hs, checked);
        for j in i..end {
            emit_native_rc(&mut a, &mut rc, block, j, region0, seg_cost, &mut cold, &mut exit)?;
        }
        rc.materialize_flags(&mut a);
        let s_out = rc.clone();
        a.add_imm(X, COUNTER, COUNTER, seg_cost);
        a.cmp(X, COUNTER, LIMIT);
        let out = exit(&mut a, end, S_STOP, LINK_NONE);
        a.b_cond(Cond::Hs, stop_lbl);
        a.bind(next);
        cold.push(ColdTask::StopExit { snap: s_out.clone(), label: stop_lbl, out });
        cold.push(ColdTask::CheckedSeg { s_in, s_out, checked, next, i, end });
        i = end;
    }

    // Flush cached guest state before block-end exits / chaining
    rc.flush_and_clear(&mut a);

    // Block end: hot chaining with per-exit monomorphic `br x16` (or self-loop `b block_top`)
    let last = &block.ops[n - 1];
    let branch = (n >= 2 && last.flags & F_SLOT != 0).then(|| &block.ops[n - 2]);
    let delay_off = reg_off(offset_of!(Sh2Registers, next_op_in_delay_slot));
    let emit_static_chain_or_self =
        |a: &mut Asm, exit: &mut ExitFn<'_>, k: usize, next_pc: u32, clear_delay: bool| {
            let out = exit(a, n, S_END, k as u8);
            if next_pc == block.start {
                a.cmp_imm(X, TICKS, n as u32);
                a.b_cond(Cond::Lo, out);
                a.ldr(W, R(14), CTX, CTX_WATCH);
                a.mov_imm32(R(15), block.start);
                a.sub(W, R(14), R(14), R(15));
                a.cmp_imm(W, R(14), (2 * n) as u32);
                a.b_cond(Cond::Lo, out);
                a.sub_imm(X, TICKS, TICKS, n as u32);
                a.add_imm(X, TOTAL, TOTAL, n as u32);
                if clear_delay {
                    a.strb(ZR, REGS, delay_off);
                }
                a.b(block_top);
            } else {
                let chain_stub = if clear_delay { stubs.chain_static_clear } else { stubs.chain_static_keep };
                let chain_off = (offset_of!(Block, chain) + k * 8) as u32;
                a.ldr(X, R(12), BLOCK, chain_off);
                a.mov_imm32(R(15), next_pc);
                a.movz(W, R(11), n as u16, 0);
                a.bl_abs_deferred(chain_stub);
                a.cbz(X, R(16), out);
                a.br(R(16));
            }
        };

    if (last.kind == K_CALLOUT && inline_read(last).is_none() && inline_write(last).is_none())
        || branch.is_some_and(|b| b.kind != K_DELAYED)
    {
        // Ends in a non-inlined call-out, or in the slot of a call-out delayed branch (RTE): no link
        let out = exit(&mut a, n, S_END, LINK_NONE);
        a.b(out);
    } else if let Some(b) = branch.or((last.kind == K_DELAYED).then_some(last)) {
        // With the slot in the block the static successor can be chained; a delayed branch
        // whose slot is not in the block leaves it pending and is never chained
        let has_slot = branch.is_some();
        match b.opcode >> 12 {
            0xA | 0xB => {
                if has_slot {
                    emit_static_chain_or_self(&mut a, &mut exit, 0, b.imm, true);
                } else {
                    let out = exit(&mut a, n, S_END, LINK_NONE);
                    a.b(out);
                }
            }
            0x8 => {
                if has_slot {
                    let untaken_lbl = a.new_label();
                    a.ldrb(T0, REGS, delay_off);
                    a.cbz(W, T0, untaken_lbl);
                    emit_static_chain_or_self(&mut a, &mut exit, 1, b.imm, true);
                    a.bind(untaken_lbl);
                    emit_static_chain_or_self(&mut a, &mut exit, 0, b.addr.wrapping_add(4), false);
                } else {
                    let taken = exit(&mut a, n, S_END, 1);
                    let untaken = exit(&mut a, n, S_END, 0);
                    a.ldrb(T0, REGS, delay_off);
                    a.cbnz(W, T0, taken);
                    a.b(untaken);
                }
            }
            _ => {
                let out = exit(&mut a, n, S_END, 1);
                if has_slot {
                    a.ldr(W, R(15), REGS, reg_off(offset_of!(Sh2Registers, next_pc)));
                    a.movz(W, R(11), n as u16, 0);
                    a.bl_abs_deferred(stubs.dispatch_pc_clear);
                    a.cbz(X, R(16), out);
                    a.br(R(16));
                } else {
                    a.b(out);
                }
            }
        }
    } else {
        emit_static_chain_or_self(&mut a, &mut exit, 0, last.addr.wrapping_add(2), false);
    }

    // Hot `TakenBranch` targets (both self-loop and inter-block) stay in HOT for L1i locality.
    let mut self_loop_fails: Vec<(super::a64::Label, super::a64::Label, u32)> = Vec::new();
    let mut taken_fnotes: Vec<(super::a64::Label, super::a64::Label, usize)> = Vec::new();
    for task in &cold {
        if let ColdTask::TakenBranch { snap, is_bt, label, out, target, i, has_note, add_cycles } =
            task
        {
            a.bind(*label);
            snap.emit_flush_dirty_known_t(&mut a, *is_bt);
            if *target == block.start {
                if *has_note {
                    let skip = a.new_label();
                    let note_lbl = a.new_label();
                    emit_f_note_check(&mut a, *target, skip, note_lbl);
                    a.bind(skip);
                    taken_fnotes.push((note_lbl, skip, *i));
                }
                let fail = a.new_label();
                a.add_imm(X, COUNTER, COUNTER, *add_cycles);
                a.cmp(X, COUNTER, LIMIT);
                a.b_cond(Cond::Hs, fail);
                a.cmp_imm(X, TICKS, n as u32);
                a.b_cond(Cond::Lo, fail);
                a.ldr(W, R(14), CTX, CTX_WATCH);
                a.mov_imm32(R(15), *target);
                a.sub(W, R(14), R(14), R(15));
                a.cmp_imm(W, R(14), (2 * n) as u32);
                a.b_cond(Cond::Lo, fail);
                a.sub_imm(X, TICKS, TICKS, n as u32);
                a.add_imm(X, TOTAL, TOTAL, (*i + 1) as u32);
                a.b(block_top);
                self_loop_fails.push((fail, *out, *target));
            } else {
                a.mov_imm32(T0, *target);
                a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, pc)));
                a.add_imm(W, T0, T0, 2);
                a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, next_pc)));
                if *has_note {
                    let skip = a.new_label();
                    let note_lbl = a.new_label();
                    emit_f_note_check(&mut a, *target, skip, note_lbl);
                    a.bind(skip);
                    taken_fnotes.push((note_lbl, skip, *i));
                }
                a.add_imm(X, COUNTER, COUNTER, *add_cycles);
                a.cmp(X, COUNTER, LIMIT);
                a.b_cond(Cond::Hs, *out);
                let chain_off = (offset_of!(Block, chain) + 8) as u32;
                a.ldr(X, R(12), BLOCK, chain_off);
                a.mov_imm32(R(15), *target);
                a.movz(W, R(11), (*i + 1) as u16, 0);
                a.bl_abs_deferred(stubs.chain_static_keep);
                a.cbz(X, R(16), *out);
                a.br(R(16));
            }
        }
    }

    // Cold section: rare fallbacks, checked segments, fetch-miss stubs, and exit stubs.
    a.set_cold(true);

    for (fail, out, target) in self_loop_fails {
        a.bind(fail);
        a.mov_imm32(T0, target);
        a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, pc)));
        a.add_imm(W, T0, T0, 2);
        a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, next_pc)));
        a.b(out);
    }

    for (note_lbl, skip, i) in taken_fnotes {
        a.bind(note_lbl);
        emit_tramp(&mut a, T_NOTE, i);
        a.b(skip);
    }

    for task in cold {
        match task {
            ColdTask::StopExit { snap, label, out } | ColdTask::InvalExit { snap, label, out } => {
                a.bind(label);
                snap.emit_flush_dirty(&mut a);
                a.b(out);
            }
            ColdTask::TakenBranch { .. } => {}
            ColdTask::FetchHit { label, ret_lbl, line_idx } => {
                a.bind(label);
                a.movz(W, R(11), line_idx as u16, 0);
                a.bl_abs_deferred(stubs.fetch_hit);
                a.b(ret_lbl);
            }
            ColdTask::FNote { snap, cold, skip, i } => {
                a.bind(cold);
                snap.emit_flush_dirty(&mut a);
                emit_tramp(&mut a, T_NOTE, i);
                snap.emit_reload_all(&mut a);
                a.b(skip);
            }
            ColdTask::CheckedSeg { s_in, s_out, checked, next, i, end } => {
                a.bind(checked);
                s_in.emit_flush_dirty(&mut a);
                for j in i..end {
                    let cost = u32::from(block.ops[j].cost);
                    emit_native(&mut a, block, j, region0, cost, stubs, &mut exit)
                        .expect("fast segment already validated native ops");
                    a.add_imm(X, COUNTER, COUNTER, cost);
                    a.cmp(X, COUNTER, LIMIT);
                    let out = exit(&mut a, j + 1, S_STOP, LINK_NONE);
                    a.b_cond(Cond::Hs, out);
                }
                s_out.emit_reload_all(&mut a);
                a.b(next);
            }
        }
    }

fn emit_store_data_ctx(a: &mut Asm, addr: u32, opcode: u16) {
    a.movz(W, T0, 1, 0);
    a.strb(T0, CPU, DATA_CTX_OFF);
    a.mov_imm32(T0, addr);
    a.str(W, T0, CPU, DATA_CTX_OFF + 8);
    a.movz(W, T0, opcode, 0);
    a.strh(T0, CPU, DATA_CTX_OFF + 12);
}

fn emit_materialize_op(a: &mut Asm, op: &Uop) {
    if op.kind == K_DELAYED {
        a.mov_imm32(T0, op.addr.wrapping_add(2));
        a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, pc)));
    } else if op.flags & F_SLOT != 0 {
        a.ldr(W, T0, REGS, reg_off(offset_of!(Sh2Registers, next_pc)));
        a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, pc)));
        a.add_imm(W, T0, T0, 2);
        a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, next_pc)));
        a.strb(ZR, REGS, reg_off(offset_of!(Sh2Registers, next_op_in_delay_slot)));
    } else {
        a.mov_imm32(T0, op.addr.wrapping_add(2));
        a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, pc)));
        a.mov_imm32(T0, op.addr.wrapping_add(4));
        a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, next_pc)));
        a.strb(ZR, REGS, reg_off(offset_of!(Sh2Registers, next_op_in_delay_slot)));
    }
    emit_store_data_ctx(a, op.addr, op.opcode);
}

    for (label, value) in exits {
        a.bind(label);
        let status = (value >> 32) & 0xFF;
        let op_idx = (value as u32 as usize) - 1;
        match status {
            S_STOP | S_INVAL => emit_materialize_op(&mut a, &block.ops[op_idx]),
            S_TAKEN => {
                let op = &block.ops[op_idx];
                emit_store_data_ctx(&mut a, op.addr, op.opcode);
            }
            S_END if block.last_materialize => emit_materialize_op(&mut a, &block.ops[n - 1]),
            _ => {}
        }
        a.movz(X, R(0), value as u16, 0);
        a.movk(X, R(0), (value >> 32) as u16, 32);
        a.b_abs_deferred(stubs.exit);
    }
    Some(a)
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
                match exit {
                    None => {
                        ctx.stopped = 0;
                        0
                    }
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
                ctx.watch = cpu.idle_watch.0.head;
                0
            }
        }
    }
}

/// Out-of-line slow memory read called from `emit_stub_read` when address/cache misses fast path.
extern "C" fn read_slow<Bus: BusInterface>(ctx: *mut Ctx, arg: u64) -> u64 {
    // SAFETY: `ctx` holds exclusive live pointers to `cpu`, `bus`, and `block`.
    unsafe {
        let ctx = &mut *ctx;
        let cpu = &mut *ctx.cpu;
        let block = &*ctx.block;
        let bus = &mut *ctx.bus.cast::<Bus>();
        let addr = arg as u32;
        let uop_idx = ((arg >> 32) & 0xFFFF) as usize;
        let size_idx = (arg >> 56) as u8;
        let now = bus.idle_cycle_counter();
        bus.increment_cycle_counter(ctx.counter - now);
        let op = block.ops.get_unchecked(uop_idx);
        cpu.data_ctx = AccessContext::Data { pc: op.addr, opcode: op.opcode };
        let old_code_gen = cpu.cache.code_gen();
        let val: u32 = if (addr >> 29) == 0 && cpu.cache.enabled() {
            match size_idx {
                0 => cpu.read_cached_miss_byte(addr, bus) as i8 as i32 as u32,
                1 => cpu.read_cached_miss_word(addr, bus) as i16 as i32 as u32,
                _ => cpu.read_cached_miss_longword(addr, bus),
            }
        } else {
            match size_idx {
                0 => cpu.read_byte(addr, bus) as i8 as i32 as u32,
                1 => cpu.read_word(addr, bus) as i16 as i32 as u32,
                _ => cpu.read_longword(addr, bus),
            }
        };
        bus.increment_cycle_counter(1);
        ctx.counter = bus.idle_cycle_counter();
        let inval = (op.flags & super::F_MEM != 0)
            && cpu.cache.code_gen() != old_code_gen
            && !block.valid(&cpu.cache);
        (u64::from(inval) << 32) | u64::from(val)
    }
}

/// Out-of-line slow memory write called from `emit_stub_write` when address misses fast path.
extern "C" fn write_slow<Bus: BusInterface>(ctx: *mut Ctx, arg: u64) -> u64 {
    // SAFETY: `ctx` holds exclusive live pointers to `cpu`, `bus`, and `block`.
    unsafe {
        let ctx = &mut *ctx;
        let cpu = &mut *ctx.cpu;
        let block = &*ctx.block;
        let bus = &mut *ctx.bus.cast::<Bus>();
        let addr = arg as u32;
        let uop_idx = ((arg >> 32) & 0xFFFF) as usize;
        let size_idx = (arg >> 56) as u8;
        let val = ctx.write_val;
        let now = bus.idle_cycle_counter();
        bus.increment_cycle_counter(ctx.counter - now);
        let op = block.ops.get_unchecked(uop_idx);
        cpu.data_ctx = AccessContext::Data { pc: op.addr, opcode: op.opcode };
        let old_code_gen = cpu.cache.code_gen();
        match size_idx {
            0 => cpu.write_byte(addr, val as u8, bus),
            1 => cpu.write_word(addr, val as u16, bus),
            _ => cpu.write_longword(addr, val, bus),
        }
        bus.increment_cycle_counter(1);
        ctx.counter = bus.idle_cycle_counter();
        let inval = (op.flags & super::F_MEM != 0)
            && cpu.cache.code_gen() != old_code_gen
            && !block.valid(&cpu.cache);
        u64::from(inval)
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

/// Runs compiled `block` using `enter_stub` and pre-populated `ctx`.
///
/// # Safety
/// `ctx`, `cpu`, `bus`, and `block` must remain valid and unaliased for the call; `enter_stub`
/// must be the shared entry stub of `call`'s current code store, `block_n`/`target_entry` must
/// match `(*block).n`/`(*block).entry`, and `ctx` must already have `init_static` and `set_bus`
/// applied for `(cpu, bus)`.
#[inline(always)]
pub(crate) unsafe fn run_with_ctx<Bus: BusInterface>(
    ctx: *mut Ctx,
    cpu: *mut Sh2,
    bus: *mut Bus,
    block: *const Block,
    block_n: usize,
    target_entry: usize,
    enter_stub: usize,
    ticks: u64,
    call: unsafe fn(usize, *mut Ctx, usize, u64, u64, *const Block, u64) -> JitRet,
) -> (Exit, *const Block) {
    unsafe {
        (*ctx).regs = &raw mut (*cpu).registers;
        (*ctx).cpu = cpu;
        (*ctx).bus = bus.cast();
        let counter = (*bus).idle_cycle_counter();
        let limit = (*bus).idle_cycle_limit();
        let ticks_left = ticks - block_n as u64;
        #[cfg(all(
            not(test),
            target_arch = "aarch64",
            any(target_os = "android", target_os = "linux"),
            feature = "jit-arm64"
        ))]
        let ret = {
            let _ = call;
            let f: unsafe extern "C" fn(*mut Ctx, usize, u64, u64, *const Block, u64) -> JitRet =
                std::mem::transmute(enter_stub);
            f(ctx, target_entry, counter, limit, block, ticks_left)
        };
        #[cfg(any(
            test,
            not(all(
                target_arch = "aarch64",
                any(target_os = "android", target_os = "linux"),
                feature = "jit-arm64"
            ))
        ))]
        let ret = call(enter_stub, ctx, target_entry, counter, limit, block, ticks_left);
        let now = (*bus).idle_cycle_counter();
        (*bus).increment_cycle_counter(ret.counter - now);
        let c = &*ctx;
        super::stat!(s => s.native_chained_instructions += c.total);
        let exit = run_exit(ret, limit, c);
        (exit, c.block)
    }
}

#[inline(always)]
fn run_exit(ret: JitRet, limit: u64, ctx: &Ctx) -> Exit {
    let total_executed = ret.packed as u32 as usize;
    let status = (ret.packed >> 32) & 0xFF;
    let link = ((ret.packed >> 40) & 0xFF) as u8;
    match status {
        S_STOP => Exit { executed: total_executed, stopped: true, link: LINK_NONE },
        S_TAKEN => Exit { executed: total_executed, stopped: ret.counter >= limit, link: 1 },
        S_END => Exit { executed: total_executed, stopped: false, link },
        S_INVAL => Exit { executed: total_executed, stopped: ret.counter >= limit, link: LINK_NONE },
        _ => Exit { executed: total_executed, stopped: ctx.stopped != 0, link: LINK_NONE },
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

    fn test_ctx(cpu: &mut Sh2, bus: *mut (), sdram: *mut u16, counter: u64) -> Ctx {
        cpu.cache.ensure_entry_tags();
        Ctx {
            regs: &raw mut cpu.registers,
            counter,
            tramp: 0,
            cpu,
            bus,
            block: std::ptr::null(),
            n: 1,
            stopped: 0,
            total: 0,
            sdram,
            fast_table: std::ptr::null(),
            rom_ptr: std::ptr::null(),
            rom_len: 0,
            write_val: 0,
            watch: crate::idle::NO_WATCH,
            read_slow: 0,
            write_slow: 0,
            lru_masks: [0x00C7, 0x20F9, 0x14FE, 0x0BFF],
        }
    }

    #[test]
    fn access_context_repr_c_layout() {
        let mut ctx = AccessContext::Fetch;
        let ptr = (&raw mut ctx).cast::<u8>();
        unsafe {
            ptr.write(1u8);
            ptr.add(8).cast::<u32>().write(0x1234_5678);
            ptr.add(12).cast::<u16>().write(0xABCD);
        }
        assert!(matches!(ctx, AccessContext::Data { pc: 0x1234_5678, opcode: 0xABCD }));
    }

    #[test]
    fn inline_rom_reads_match_interpreter() {
        use crate::bus::{AccessContext, BusInterface, OpSize};
        struct RomBus {
            rom: Box<[u16]>,
            cycles: u64,
        }
        impl BusInterface for RomBus {
            type DebugView<'a> = crate::debug::DummySh2Debugger;
            fn read<const SIZE: u8>(&mut self, address: u32, _: AccessContext) -> u32 {
                self.cycles += if SIZE == OpSize::LONGWORD { 16 } else { 8 };
                let a = (address & 0x3F_FFFF) as usize;
                let oob = |byte_off: usize| -> u16 {
                    let idx = byte_off >> 1;
                    self.rom.get(idx).copied().unwrap_or(0xFFFF)
                };
                match SIZE {
                    OpSize::BYTE => u32::from(oob(a).to_be_bytes()[a & 1]),
                    OpSize::WORD => u32::from(oob(a)),
                    _ => (u32::from(oob(a)) << 16) | u32::from(oob(a | 2)),
                }
            }
            fn read_cache_line(&mut self, address: u32, ctx: AccessContext) -> [u16; 8] {
                std::array::from_fn(|i| self.read::<{ OpSize::WORD }>(address | ((i as u32) << 1), ctx) as u16)
            }
            fn write<const SIZE: u8>(&mut self, _: u32, _: u32, _: AccessContext) {}
            fn reset(&self) -> bool { false }
            fn interrupt_level(&self) -> u8 { 0 }
            fn dma_request_0(&self) -> bool { false }
            fn dma_request_1(&self) -> bool { false }
            fn acknowledge_dreq_1(&mut self) {}
            fn serial_rx(&mut self) -> Option<u8> { None }
            fn serial_tx(&mut self, _: u8) {}
            fn increment_cycle_counter(&mut self, c: u64) { self.cycles += c; }
            fn should_stop_execution(&self) -> bool { false }
            unsafe fn native_rom_slice(&mut self) -> (*const u16, u32) {
                (self.rom.as_ptr(), (self.rom.len() * 2) as u32)
            }
            crate::impl_sh2_opcode_table!(RomBus);
        }

        let mut seed = 0x1357_9BDF_2468_ACE0u64;
        let mut next = move || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            seed
        };
        let rom: Box<[u16]> = (0..1024).map(|_| next() as u16).collect();
        let mut cpu = Sh2::new("rom-rw".into());
        let mut bus = RomBus { rom, cycles: 0 };
        let mut ref_cpu = cpu.clone();
        let mut ref_bus = RomBus { rom: bus.rom.clone(), cycles: 0 };
        let mut store = super::super::arena::Store::default();
        let opcodes = [0x6530u16, 0x6531, 0x6532, 0x6336]; // MOV.{B,W,L} @R3,R5 + MOV.L @R3+,R3

        for round in 0..300 {
            let cached_disabled_or_uncached = if round & 1 == 0 { 0x0200_0000u32 } else { 0x2200_0000u32 };
            let off = ((next() as u32) & 0xFFC) + if round % 10 == 0 { 2048 } else { 0 };
            let opcode = opcodes[round % opcodes.len()];
            let align = match opcode { 0x6530 => 0, 0x6531 => 1, _ => 3 };
            let addr = cached_disabled_or_uncached + (off & !align);
            cpu.registers.gpr[3] = addr;
            ref_cpu.registers = cpu.registers.clone();
            let uop = Uop { opcode, addr: 0x0600_0100, ..super::super::EMPTY_UOP };
            RomBus::opcode_table().decode(opcode)(&mut ref_cpu, opcode, &mut ref_bus);
            ref_bus.increment_cycle_counter(1);

            let (rom_ptr, rom_len) = unsafe { bus.native_rom_slice() };
            let mut ctx = test_ctx(&mut cpu, (&raw mut bus).cast(), std::ptr::null_mut(), bus.cycles);
            ctx.rom_ptr = rom_ptr;
            ctx.rom_len = rom_len;
            let r = inline_read(&uop).unwrap();
            let entry = store
                .publish_asm(|stubs| {
                    let mut a = Asm::new();
                    let layout = crate::cache::CpuCache::native_layout();
                    let memo_off = (offset_of!(Sh2, cache) + layout.memo_line) as u32;
                    a.stp_pre(R(29), LR, SP, -16);
                    a.mov(X, CTX, R(0));
                    a.ldr(X, REGS, CTX, CTX_REGS);
                    a.ldr(X, COUNTER, CTX, CTX_COUNTER);
                    a.ldr(X, CPU, CTX, CTX_CPU);
                    a.ldr(W, MEMO, CPU, memo_off);
                    emit_addr(&mut a, r.base, r.disp, 0);
                    a.bl_abs_deferred(stubs.read[r.size as usize >> 1]);
                    let miss = a.new_label();
                    a.cbnz(W, T1, miss);
                    if r.post_inc && let AddrBase::Reg(m) = r.base {
                        a.add_imm(W, T1, T0, r.size);
                        a.str(W, T1, REGS, gpr(m));
                    }
                    let RegTarget::Gpr(d) = r.dest else { unreachable!() };
                    a.str(W, R(15), REGS, gpr(d));
                    a.str(W, MEMO, CPU, memo_off);
                    a.str(X, COUNTER, CTX, CTX_COUNTER);
                    a.movz(X, R(0), 1, 0);
                    a.ldp_post(R(29), LR, SP, 16);
                    a.ret();
                    a.bind(miss);
                    a.movz(X, R(0), 0, 0);
                    a.ldp_post(R(29), LR, SP, 16);
                    a.ret();
                    Some(a)
                })
                .flatten()
                .unwrap();
            let fast = unsafe {
                (super::super::arena::Store::CALL)(entry, &raw mut ctx, 0, 0, 0, std::ptr::null(), 0)
            }
            .packed == 1;
            if fast {
                bus.cycles = ctx.counter;
                assert_eq!(cpu.registers, ref_cpu.registers, "round {round} op {opcode:04x} addr {addr:08x}");
                assert_eq!(bus.cycles, ref_bus.cycles, "round {round} cycles");
            } else {
                assert!(off >= 2048, "unexpected ROM fallback at in-bounds offset {off}");
                cpu = ref_cpu.clone();
                bus.cycles = ref_bus.cycles;
            }
        }
    }

    #[test]
    fn inline_cached_reads_match_the_cache() {
        let mut seed = 0x9E37_79B9_7F4A_7C15u64;
        let mut next = move || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            seed
        };
        let mut store = super::super::arena::Store::default();
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

            let mut ctx = test_ctx(&mut cpu, std::ptr::null_mut(), std::ptr::null_mut(), 0);
            let entry = store
                .publish_asm(|stubs| {
                    let mut a = Asm::new();
                    let layout = crate::cache::CpuCache::native_layout();
                    let memo_off = (offset_of!(Sh2, cache) + layout.memo_line) as u32;
                    a.stp_pre(R(29), LR, SP, -16);
                    a.mov(X, CTX, R(0));
                    a.ldr(X, REGS, CTX, CTX_REGS);
                    a.ldr(X, COUNTER, CTX, CTX_COUNTER);
                    a.ldr(X, CPU, CTX, CTX_CPU);
                    a.ldr(W, MEMO, CPU, memo_off);
                    emit_addr(&mut a, read.base, read.disp, 0);
                    a.bl_abs_deferred(stubs.read[read.size as usize >> 1]);
                    let miss = a.new_label();
                    a.cbnz(W, T1, miss);
                    let RegTarget::Gpr(d) = read.dest else { unreachable!() };
                    a.str(W, R(15), REGS, gpr(d));
                    a.str(W, MEMO, CPU, memo_off);
                    a.movz(X, R(0), 1, 0);
                    a.ldp_post(R(29), LR, SP, 16);
                    a.ret();
                    a.bind(miss);
                    a.movz(X, R(0), 0, 0);
                    a.ldp_post(R(29), LR, SP, 16);
                    a.ret();
                    Some(a)
                })
                .flatten()
                .unwrap();
            let hit = unsafe {
                (super::super::arena::Store::CALL)(entry, &raw mut ctx, 0, 0, 0, std::ptr::null(), 0)
            }
            .packed == 1;
            assert_eq!(hit, expected.is_some(), "round {round}: hit/miss differs at {address:08X}");
            if let Some(value) = expected {
                assert_eq!(cpu.registers.gpr[5], value, "round {round}: value at {address:08X}");
                let state = |c: &Sh2| bincode::encode_to_vec(&c.cache, bincode::config::standard()).unwrap();
                assert!(state(&cpu) == state(&reference), "round {round}: cache state (LRU) differs");
                assert_eq!(cpu.cache.fetch_memo_line_ptr().cast::<u32>().is_null(), false);
            }
        }
    }

    #[test]
    fn inline_sdram_and_data_array_reads_writes_match_interpreter() {
        use crate::bus::{AccessContext, BusInterface, OpSize};
        struct SdramBus {
            sdram: Box<[u16; 131072]>,
            cycles: u64,
        }
        impl BusInterface for SdramBus {
            type DebugView<'a> = crate::debug::DummySh2Debugger;
            fn read<const SIZE: u8>(&mut self, address: u32, _: AccessContext) -> u32 {
                self.cycles += 10;
                let a = (address & 0x3FFFF) as usize;
                match SIZE {
                    OpSize::BYTE => u32::from(self.sdram[a >> 1].to_be_bytes()[a & 1]),
                    OpSize::WORD => u32::from(self.sdram[(a >> 1) & !0]),
                    _ => {
                        let w = (a >> 1) & !1;
                        (u32::from(self.sdram[w]) << 16) | u32::from(self.sdram[w + 1])
                    }
                }
            }
            fn read_cache_line(&mut self, address: u32, _ctx: AccessContext) -> [u16; 8] {
                self.cycles += 10;
                let base = ((address & 0x3FFF0) >> 1) as usize;
                std::array::from_fn(|i| self.sdram[base + i])
            }
            fn write<const SIZE: u8>(&mut self, address: u32, value: u32, _: AccessContext) {
                self.cycles += 1;
                let a = (address & 0x3FFFF) as usize;
                match SIZE {
                    OpSize::BYTE => {
                        let mut bytes = self.sdram[a >> 1].to_be_bytes();
                        bytes[a & 1] = value as u8;
                        self.sdram[a >> 1] = u16::from_be_bytes(bytes);
                    }
                    OpSize::WORD => self.sdram[a >> 1] = value as u16,
                    _ => {
                        let w = (a >> 1) & !1;
                        self.sdram[w] = (value >> 16) as u16;
                        self.sdram[w + 1] = value as u16;
                    }
                }
            }
            fn reset(&self) -> bool { false }
            fn interrupt_level(&self) -> u8 { 0 }
            fn dma_request_0(&self) -> bool { false }
            fn dma_request_1(&self) -> bool { false }
            fn acknowledge_dreq_1(&mut self) {}
            fn serial_rx(&mut self) -> Option<u8> { None }
            fn serial_tx(&mut self, _: u8) {}
            fn increment_cycle_counter(&mut self, c: u64) { self.cycles += c; }
            fn should_stop_execution(&self) -> bool { false }
            unsafe fn native_sdram_ptr(&mut self) -> *mut u16 { self.sdram.as_mut_ptr() }
            crate::impl_sh2_opcode_table!(SdramBus);
        }

        let mut seed = 0x517C_C1B7_2722_0A95u64;
        let mut next = move || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            seed
        };

        let mut cpu = Sh2::new("inline-rw".into());
        let mut bus = SdramBus { sdram: vec![0; 131072].into_boxed_slice().try_into().unwrap(), cycles: 0 };
        cpu.cache.write_control(1);
        for _ in 0..40 {
            let line = 0x0600_0000 + ((next() as u32) & 0x3FF0);
            let words: [u16; 8] = std::array::from_fn(|_| next() as u16);
            let _ = cpu.cache.replace(line, words);
        }
        // Mark one code line in way 1, entry 3 so fallback on code-line store is verified
        cpu.cache.mark_code_line(1, 3);

        let mut ref_cpu = cpu.clone();
        let mut ref_bus = SdramBus { sdram: bus.sdram.clone(), cycles: 0 };
        let mut store = super::super::arena::Store::default();

        // Cases: cached SDRAM write hit/miss/byte/word/long/pre-dec, uncached SDRAM, Region 6 read/write
        let opcodes: &[u16] = &[
            0x2340, 0x2341, 0x2342, // MOV.{B,W,L} R4,@R3
            0x2336, // MOV.L R3,@-R3 (pre-dec with m == n)
            0x6534, 0x6535, 0x6536, // MOV.{B,W,L} @R3+,R5
            0x6336, // MOV.L @R3+,R3 (post-inc with m == n)
        ];
        for round in 0..400 {
            let region = match round % 4 {
                0 => 0x0600_0000u32,
                1 => 0x2600_0000u32,
                2 => 0xC000_0000u32,
                _ => 0x0600_0030u32, // may hit entry 3 code line
            };
            let offset = if region == 0xC000_0000 { (next() as u32) & 0xFFC } else { (next() as u32) & 0x3FFFC };
            let addr = region + offset + ((next() & 3) as u32);
            let opcode = opcodes[round % opcodes.len()];
            cpu.registers.gpr[3] = addr & !3;
            cpu.registers.gpr[4] = next() as u32;
            ref_cpu.registers = cpu.registers.clone();
            let uop = Uop { opcode, addr: 0x0600_0100, ..super::super::EMPTY_UOP };

            let table = SdramBus::opcode_table();
            table.decode(opcode)(&mut ref_cpu, opcode, &mut ref_bus);
            ref_bus.increment_cycle_counter(1);

            let sdram_ptr = unsafe { bus.native_sdram_ptr() };
            let mut ctx = test_ctx(&mut cpu, (&raw mut bus).cast(), sdram_ptr, bus.cycles);
            let entry = store
                .publish_asm(|stubs| {
                    let mut a = Asm::new();
                    let layout = crate::cache::CpuCache::native_layout();
                    let memo_off = (offset_of!(Sh2, cache) + layout.memo_line) as u32;
                    a.stp_pre(R(29), LR, SP, -16);
                    a.mov(X, CTX, R(0));
                    a.ldr(X, REGS, CTX, CTX_REGS);
                    a.ldr(X, COUNTER, CTX, CTX_COUNTER);
                    a.ldr(X, CPU, CTX, CTX_CPU);
                    a.ldr(W, MEMO, CPU, memo_off);
                    let slow = a.new_label();
                    if let Some(r) = inline_read(&uop) {
                        emit_addr(&mut a, r.base, r.disp, 0);
                        a.movz(W, R(0), 0, 0);
                        a.bl_abs_deferred(stubs.read[r.size as usize >> 1]);
                        a.cbnz(W, T1, slow);
                        if r.post_inc && let AddrBase::Reg(m) = r.base {
                            a.add_imm(W, T1, T0, r.size);
                            a.str(W, T1, REGS, gpr(m));
                        }
                        let RegTarget::Gpr(d) = r.dest else { unreachable!() };
                        a.str(W, R(15), REGS, gpr(d));
                    } else if let Some(w) = inline_write(&uop) {
                        let RegTarget::Gpr(s) = w.src else { unreachable!() };
                        a.ldr(W, R(0), REGS, gpr(s));
                        emit_addr(&mut a, w.base, w.disp, if w.pre_dec { w.size } else { 0 });
                        a.movz(W, R(13), 0, 0);
                        a.bl_abs_deferred(stubs.write[w.size as usize >> 1]);
                        a.cbnz(W, T1, slow);
                        if w.pre_dec && let AddrBase::Reg(n) = w.base {
                            a.str(W, T0, REGS, gpr(n));
                        }
                    } else {
                        unreachable!();
                    }
                    a.str(W, MEMO, CPU, memo_off);
                    a.str(X, COUNTER, CTX, CTX_COUNTER);
                    a.movz(X, R(0), 1, 0);
                    a.ldp_post(R(29), LR, SP, 16);
                    a.ret();
                    a.bind(slow);
                    a.movz(X, R(0), 0, 0);
                    a.ldp_post(R(29), LR, SP, 16);
                    a.ret();
                    Some(a)
                })
                .flatten()
                .unwrap();
            let fast = unsafe {
                (super::super::arena::Store::CALL)(entry, &raw mut ctx, 0, 0, 0, std::ptr::null(), 0)
            }
            .packed == 1;
            if fast {
                bus.cycles = ctx.counter;
                assert_eq!(cpu.registers, ref_cpu.registers, "round {round} op {opcode:04x} addr {addr:08x}: regs");
                assert_eq!(bus.cycles, ref_bus.cycles, "round {round} op {opcode:04x} addr {addr:08x}: cycles");
                assert_eq!(*bus.sdram, *ref_bus.sdram, "round {round} op {opcode:04x} addr {addr:08x}: sdram");
                let enc = |c: &Sh2| bincode::encode_to_vec(c, bincode::config::standard()).unwrap();
                assert_eq!(enc(&cpu), enc(&ref_cpu), "round {round} op {opcode:04x} addr {addr:08x}: serialized state");
                assert_eq!(cpu.cache.epoch(), ref_cpu.cache.epoch(), "round {round}: epoch");
            } else {
                cpu = ref_cpu.clone();
                bus.sdram = ref_bus.sdram.clone();
                bus.cycles = ref_bus.cycles;
            }
        }
    }

    struct LimitBus(u64, u64);
    impl BusInterface for LimitBus {
        type DebugView<'a> = crate::debug::DummySh2Debugger;
        fn read<const SIZE: u8>(&mut self, _: u32, _: AccessContext) -> u32 { 0 }
        fn read_cache_line(&mut self, _: u32, _: AccessContext) -> [u16; 8] { [0; 8] }
        fn write<const SIZE: u8>(&mut self, _: u32, _: u32, _: AccessContext) {}
        fn reset(&self) -> bool { false }
        fn interrupt_level(&self) -> u8 { 0 }
        fn dma_request_0(&self) -> bool { false }
        fn dma_request_1(&self) -> bool { false }
        fn acknowledge_dreq_1(&mut self) {}
        fn serial_rx(&mut self) -> Option<u8> { None }
        fn serial_tx(&mut self, _: u8) {}
        fn increment_cycle_counter(&mut self, cycles: u64) { self.0 += cycles; }
        fn should_stop_execution(&self) -> bool { self.0 >= self.1 }
        fn idle_cycle_counter(&self) -> u64 { self.0 }
        fn idle_cycle_limit(&self) -> u64 { self.1 }
        crate::impl_sh2_opcode_table!(LimitBus);
    }

    #[test]
    fn inline_revalidate_in_chain_stub_handles_unrelated_code_gen_bump() {
        let mut cpu = Sh2::new("reval-chain".into());
        cpu.cache.write_control(1);
        // Load two non-looping cached blocks: 0x0600_0000 (add #1, r0; bra 0x0600_0010; nop)
        // and 0x0600_0010 (add #2, r0; rts; nop)
        let mut line0 = [0x0009u16; 8];
        line0[0] = 0x7001; // add #1, R0
        line0[1] = 0xA005; // bra +10 -> 0x0600_0010 (PC 0x0600_0002 + 4 + 10)
        line0[2] = 0x0009; // nop
        let mut line1 = [0x0009u16; 8];
        line1[0] = 0x7002; // add #2, R0
        line1[1] = 0x000B; // rts
        line1[2] = 0x0009; // nop
        let _ = cpu.cache.replace(0x0600_0000, line0);
        let _ = cpu.cache.replace(0x0600_0010, line1);
        // Fill all 4 ways of unrelated entry 10 and mark them as code so replacing a 5th tag evicts
        // one and bumps `code_gen`.
        for tag in 0..4u32 {
            let addr = 0x0600_00A0 + (tag << 10);
            let _ = cpu.cache.replace(addr, [0x0009u16; 8]);
            let (entry, way) = cpu.cache.lookup(addr).unwrap();
            cpu.cache.mark_code_line(way, entry);
        }

        let mut bus = LimitBus(0, 1000);
        cpu.registers.pc = 0x0600_0000;
        cpu.registers.next_pc = 0x0600_0002;
        cpu.registers.pr = 0x2000_0000; // uncached sentinel after RTS
        cpu.jit_prepare_slice(&mut bus);

        // First slice forms & compiles both blocks (cfg!(test) has COMPILE_AFTER = 1) and links 0->1.
        let _ = cpu.jit_run(&mut bus, 6, false);
        cpu.registers.pc = 0x0600_0000;
        cpu.registers.next_pc = 0x0600_0002;
        cpu.registers.next_op_in_delay_slot = false;
        let _ = cpu.jit_run(&mut bus, 6, false);
        let slot1 = cpu.jit.table.iter().position(|b| b.start == 0x0600_0010).unwrap();
        assert_ne!(cpu.jit.table[slot1].entry, 0);

        // Bump global `code_gen` by replacing one of the marked ways in unrelated entry 10.
        let old_gen = cpu.cache.code_gen();
        let _ = cpu.cache.replace(0x0600_10A0, [0x0009u16; 8]);
        assert_ne!(cpu.cache.code_gen(), old_gen);
        assert_eq!(cpu.jit.table[slot1].seen.get(), old_gen);

        // Second run enters at 0x0600_0000 and chains statically to stale-seen 0x0600_0010:
        // `emit_stub_chain_static::revalidate_slow` must refresh `slot1.seen` natively.
        cpu.registers.pc = 0x0600_0000;
        cpu.registers.next_pc = 0x0600_0002;
        cpu.registers.next_op_in_delay_slot = false;
        let (executed, stopped) = cpu.jit_run(&mut bus, 6, false);
        assert!(!stopped);
        assert_eq!(executed, 6);
        assert_eq!(cpu.jit.table[slot1].seen.get(), cpu.cache.code_gen());
        assert_eq!(cpu.registers.gpr[0], 9);
    }

    #[test]
    fn region6_r6_cache_reuses_compiled_overlay() {
        let mut cpu = Sh2::new("r6-overlay".into());
        let mut bus = LimitBus(0, 1000);
        let write_overlay = |c: &mut Sh2, prog: &[u16]| {
            for (i, &w) in prog.iter().enumerate() {
                c.cache.write_data_array_u16(0xC000_0000 + (i as u32) * 2, w);
            }
        };
        let prog_a = [0x7003u16, 0x000B, 0x0009]; // add #3, r0; rts; nop
        let prog_b = [0x7005u16, 0x000B, 0x0009]; // add #5, r0; rts; nop

        write_overlay(&mut cpu, &prog_a);
        cpu.registers.pc = 0xC000_0000;
        cpu.registers.next_pc = 0xC000_0002;
        cpu.registers.pr = 0x2000_0000;
        cpu.jit_prepare_slice(&mut bus);
        let (exec_a, _) = cpu.jit_run(&mut bus, 3, false);
        assert_eq!(exec_a, 3);
        assert_eq!(cpu.registers.gpr[0], 3);
        let slot_a = cpu.jit.table.iter().position(|b| b.start == 0xC000_0000).unwrap();
        let entry_a = cpu.jit.table[slot_a].entry;
        assert_ne!(entry_a, 0);

        // Overwrite with overlay B at the same PC
        write_overlay(&mut cpu, &prog_b);
        cpu.registers.pc = 0xC000_0000;
        cpu.registers.next_pc = 0xC000_0002;
        cpu.registers.next_op_in_delay_slot = false;
        let (exec_b, _) = cpu.jit_run(&mut bus, 3, false);
        assert_eq!(exec_b, 3);
        assert_eq!(cpu.registers.gpr[0], 8);
        let entry_b = cpu.jit.table[slot_a].entry;
        assert_ne!(entry_b, 0);
        assert_ne!(entry_b, entry_a);

        // Restore overlay A: `jit_lookup` must reuse `entry_a` directly without recompiling!
        write_overlay(&mut cpu, &prog_a);
        cpu.registers.pc = 0xC000_0000;
        cpu.registers.next_pc = 0xC000_0002;
        cpu.registers.next_op_in_delay_slot = false;
        let (exec_a2, _) = cpu.jit_run(&mut bus, 3, false);
        assert_eq!(exec_a2, 3);
        assert_eq!(cpu.registers.gpr[0], 11);
        assert_eq!(cpu.jit.table[slot_a].entry, entry_a);
    }

    #[test]
    fn shared_code_pool_reuses_compiled_block_across_two_cpus() {
        let mut line = [0x0009u16; 8];
        line[0] = 0x7007; // add #7, R0
        line[1] = 0x000B; // rts
        line[2] = 0x0009; // nop

        let mut master = Sh2::new("Master".into());
        master.cache.write_control(1);
        let _ = master.cache.replace(0x0600_0000, line);
        master.registers.pc = 0x0600_0000;
        master.registers.next_pc = 0x0600_0002;
        master.registers.pr = 0x2000_0000;
        let mut bus_m = LimitBus(0, 1000);
        master.jit_prepare_slice(&mut bus_m);
        let (exec_m, _) = master.jit_run(&mut bus_m, 3, false);
        assert_eq!(exec_m, 3);
        assert_eq!(master.registers.gpr[0], 7);
        let slot_m = master.jit.table.iter().position(|b| b.start == 0x0600_0000).unwrap();
        let entry_m = master.jit.table[slot_m].entry;
        assert_ne!(entry_m, 0);

        // Slave SH-2 on the same thread must immediately reuse `entry_m` on first formation.
        let mut slave = Sh2::new("Slave".into());
        slave.cache.write_control(1);
        let _ = slave.cache.replace(0x0600_0000, line);
        slave.registers.pc = 0x0600_0000;
        slave.registers.next_pc = 0x0600_0002;
        slave.registers.pr = 0x2000_0000;
        let mut bus_s = LimitBus(0, 1000);
        slave.jit_prepare_slice(&mut bus_s);
        let (exec_s, _) = slave.jit_run(&mut bus_s, 3, false);
        assert_eq!(exec_s, 3);
        assert_eq!(slave.registers.gpr[0], 7);
        let slot_s = slave.jit.table.iter().position(|b| b.start == 0x0600_0000).unwrap();
        assert_eq!(slave.jit.table[slot_s].entry, entry_m);
    }
}
