//! First piece of the AArch64 block compiler: translation of register-only SH-2 instructions.
//!
//! Generated code runs with `x19 = &mut Sh2Registers` and treats guest registers and `T` as memory
//! operands (`gpr[n]`, `sr.t`), loading them into temporaries `w9`-`w11` per instruction. This is
//! the correctness baseline the register allocator of section 13 of the design will be checked
//! against; it already has the final instruction selection. Offsets are taken from the Rust
//! structs with `offset_of!`, so no layout is assumed.
//!
//! `translate` returns `false` (and emits nothing) for instructions it does not handle; the block
//! compiler turns those into call-outs.

#![allow(dead_code)]

use super::a64::{Asm, Cond, R, Size::W, Size::X, ZR};
use crate::registers::{Sh2Registers, StatusRegister};
use std::mem::offset_of;

/// Guest register file pointer in generated code
pub(crate) const REGS: R = R(19);
pub(crate) const T_REG: R = R(8);
const POOL: [R; 7] = [R(1), R(2), R(3), R(4), R(5), R(6), R(7)];
const EMPTY_GUEST: u8 = 0xFF;

const T0: R = R(9);
const T1: R = R(10);
const T2: R = R(11);
const T3: R = R(12);
const T4: R = R(13);
const T5: R = R(14);
const T6: R = R(15);

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum TState {
    Mem,
    Reg { dirty: bool },
    Flags(Cond),
}

#[derive(Clone, Copy)]
struct Slot {
    guest: u8,
    dirty: bool,
    lru: u32,
}

impl Slot {
    const EMPTY: Self = Self { guest: EMPTY_GUEST, dirty: false, lru: 0 };
}

/// Segment-local register allocator mapping up to 7 hot guest GPRs onto `x1..=x7`
/// and keeping guest `sr.t` either in memory, `w8`, or host NZCV flags.
#[derive(Clone)]
pub(crate) struct RegCache {
    slots: [Slot; 7],
    clock: u32,
    pinned: u8,
    pub(crate) t: TState,
}

impl RegCache {
    pub(crate) fn new() -> Self {
        Self { slots: [Slot::EMPTY; 7], clock: 0, pinned: 0, t: TState::Mem }
    }

    pub(crate) fn unpin_all(&mut self) {
        self.pinned = 0;
    }

    fn alloc_slot(&mut self, a: &mut Asm, g: usize) -> (usize, bool) {
        for (idx, s) in self.slots.iter().enumerate() {
            if usize::from(s.guest) == g {
                self.clock += 1;
                self.slots[idx].lru = self.clock;
                self.pinned |= 1 << idx;
                return (idx, true);
            }
        }
        let mut pick = None;
        for (idx, s) in self.slots.iter().enumerate() {
            if s.guest == EMPTY_GUEST && (self.pinned & (1 << idx)) == 0 {
                pick = Some(idx);
                break;
            }
        }
        let idx = pick.unwrap_or_else(|| {
            self.slots
                .iter()
                .enumerate()
                .filter(|(idx, _)| (self.pinned & (1 << *idx)) == 0)
                .min_by_key(|(_, s)| s.lru)
                .map(|(idx, _)| idx)
                .expect("at most 2 GPRs pinned per instruction out of 7 slots")
        });
        let old = self.slots[idx];
        if old.guest != EMPTY_GUEST && old.dirty {
            store(a, POOL[idx], usize::from(old.guest));
        }
        self.clock += 1;
        self.slots[idx] = Slot { guest: g as u8, dirty: false, lru: self.clock };
        self.pinned |= 1 << idx;
        (idx, false)
    }

    pub(crate) fn read(&mut self, a: &mut Asm, g: usize) -> R {
        let (idx, hit) = self.alloc_slot(a, g);
        let host = POOL[idx];
        if !hit {
            load(a, host, g);
        }
        host
    }

    pub(crate) fn read_write(&mut self, a: &mut Asm, g: usize) -> R {
        let (idx, hit) = self.alloc_slot(a, g);
        let host = POOL[idx];
        if !hit {
            load(a, host, g);
        }
        self.slots[idx].dirty = true;
        host
    }

    pub(crate) fn write_only(&mut self, a: &mut Asm, g: usize) -> R {
        let (idx, _) = self.alloc_slot(a, g);
        self.slots[idx].dirty = true;
        POOL[idx]
    }

    pub(crate) fn materialize_flags(&mut self, a: &mut Asm) {
        if let TState::Flags(cond) = self.t {
            a.cset(W, T_REG, cond);
            self.t = TState::Reg { dirty: true };
        }
    }

    pub(crate) fn read_t(&mut self, a: &mut Asm) -> R {
        match self.t {
            TState::Mem => {
                a.ldrb(T_REG, REGS, t_offset());
                self.t = TState::Reg { dirty: false };
            }
            TState::Flags(cond) => {
                a.cset(W, T_REG, cond);
                self.t = TState::Reg { dirty: true };
            }
            TState::Reg { .. } => {}
        }
        T_REG
    }

    pub(crate) fn set_t_flags(&mut self, cond: Cond) {
        self.t = TState::Flags(cond);
    }

    pub(crate) fn set_t_reg(&mut self, a: &mut Asm, src: R) {
        if src != T_REG {
            a.mov(W, T_REG, src);
        }
        self.t = TState::Reg { dirty: true };
    }

    /// Flushes all dirty cached values (non-destructively from a snapshot) to `REGS`.
    pub(crate) fn emit_flush_dirty(&self, a: &mut Asm) {
        match self.t {
            TState::Flags(cond) => {
                a.cset(W, T2, cond);
                store_t_reg(a, T2);
            }
            TState::Reg { dirty: true } => store_t_reg(a, T_REG),
            TState::Mem | TState::Reg { dirty: false } => {}
        }
        for (idx, s) in self.slots.iter().enumerate() {
            if s.guest != EMPTY_GUEST && s.dirty {
                store(a, POOL[idx], usize::from(s.guest));
            }
        }
    }

    /// Variant of `emit_flush_dirty` used on taken `BT`/`BF` cold exits where `T` is statically known.
    pub(crate) fn emit_flush_dirty_known_t(&self, a: &mut Asm, known_t: bool) {
        match self.t {
            TState::Flags(_) | TState::Reg { dirty: true } => {
                a.movz(W, T0, u16::from(known_t), 0);
                store_t_reg(a, T0);
            }
            TState::Mem | TState::Reg { dirty: false } => {}
        }
        for (idx, s) in self.slots.iter().enumerate() {
            if s.guest != EMPTY_GUEST && s.dirty {
                store(a, POOL[idx], usize::from(s.guest));
            }
        }
    }

    /// Reloads every occupied register slot and `T_REG` (if active) from `REGS` after a cold path.
    pub(crate) fn emit_reload_all(&self, a: &mut Asm) {
        match self.t {
            TState::Flags(_) => panic!("cannot reload Flags across cold path"),
            TState::Reg { .. } => a.ldrb(T_REG, REGS, t_offset()),
            TState::Mem => {}
        }
        for (idx, s) in self.slots.iter().enumerate() {
            if s.guest != EMPTY_GUEST {
                load(a, POOL[idx], usize::from(s.guest));
            }
        }
    }

    pub(crate) fn flush_and_clear(&mut self, a: &mut Asm) {
        self.emit_flush_dirty(a);
        *self = Self::new();
    }
}

fn gpr(n: usize) -> u32 {
    (offset_of!(Sh2Registers, gpr) + 4 * n) as u32
}

fn reg_off(off: usize) -> u32 {
    off as u32
}

fn t_offset() -> u32 {
    reg_off(offset_of!(Sh2Registers, sr) + offset_of!(StatusRegister, t))
}

fn s_offset() -> u32 {
    reg_off(offset_of!(Sh2Registers, sr) + offset_of!(StatusRegister, s))
}

fn imask_offset() -> u32 {
    reg_off(offset_of!(Sh2Registers, sr) + offset_of!(StatusRegister, interrupt_mask))
}

fn q_offset() -> u32 {
    reg_off(offset_of!(Sh2Registers, sr) + offset_of!(StatusRegister, q))
}

fn m_offset() -> u32 {
    reg_off(offset_of!(Sh2Registers, sr) + offset_of!(StatusRegister, m))
}

fn rn(opcode: u16) -> usize {
    usize::from((opcode >> 8) & 0xF)
}

fn rm(opcode: u16) -> usize {
    usize::from((opcode >> 4) & 0xF)
}

fn load(a: &mut Asm, reg: R, n: usize) {
    a.ldr(W, reg, REGS, gpr(n));
}

fn store(a: &mut Asm, reg: R, n: usize) {
    a.str(W, reg, REGS, gpr(n));
}

fn store_t_cond(a: &mut Asm, cond: Cond) {
    a.cset(W, T2, cond);
    a.strb(T2, REGS, t_offset());
}

fn store_t_reg(a: &mut Asm, reg: R) {
    a.strb(reg, REGS, t_offset());
}

/// Rn = Rn op Rm
fn binary(a: &mut Asm, opcode: u16, op: fn(&mut Asm, R, R, R)) {
    load(a, T0, rn(opcode));
    load(a, T1, rm(opcode));
    op(a, T0, T0, T1);
    store(a, T0, rn(opcode));
}

/// Rn = op(Rm)
fn unary(a: &mut Asm, opcode: u16, op: fn(&mut Asm, R, R)) {
    load(a, T0, rm(opcode));
    op(a, T0, T0);
    store(a, T0, rn(opcode));
}

/// T = cond(Rn - Rm)
fn compare(a: &mut Asm, opcode: u16, cond: Cond) {
    load(a, T0, rn(opcode));
    load(a, T1, rm(opcode));
    a.cmp(W, T0, T1);
    store_t_cond(a, cond);
}

/// R0 = R0 op zero-extended imm8
fn logical_imm_r0(a: &mut Asm, opcode: u16, imm_op: fn(&mut Asm, R, R, u64) -> bool, reg_op: fn(&mut Asm, R, R, R)) {
    let imm = u64::from(opcode & 0xFF);
    load(a, T0, 0);
    if imm == 0 || !imm_op(a, T0, T0, imm) {
        a.mov_imm32(T1, imm as u32);
        reg_op(a, T0, T0, T1);
    }
    store(a, T0, 0);
}

pub(crate) fn translate_cached(a: &mut Asm, rc: &mut RegCache, opcode: u16) -> bool {
    rc.unpin_all();
    let n = rn(opcode);
    let m = rm(opcode);
    match opcode & 0xF00F {
        // MOV Rm,Rn
        0x6003 => {
            if m != n {
                let sm = rc.read(a, m);
                let dn = rc.write_only(a, n);
                a.mov(W, dn, sm);
            }
        }
        0x300C => {
            let dn = rc.read_write(a, n);
            let sm = if m == n { dn } else { rc.read(a, m) };
            a.add(W, dn, dn, sm);
        }
        // ADDC Rm,Rn
        0x300E => {
            let dn = rc.read_write(a, n);
            let sm = if m == n { dn } else { rc.read(a, m) };
            let tr = rc.read_t(a);
            a.cmp_imm(W, tr, 1);
            a.adcs(W, dn, dn, sm);
            rc.set_t_flags(Cond::Hs);
        }
        // ADDV Rm,Rn
        0x300F => {
            let dn = rc.read_write(a, n);
            let sm = if m == n { dn } else { rc.read(a, m) };
            a.adds(W, dn, dn, sm);
            rc.set_t_flags(Cond::Vs);
        }
        0x3008 => {
            let dn = rc.read_write(a, n);
            let sm = if m == n { dn } else { rc.read(a, m) };
            a.sub(W, dn, dn, sm);
        }
        // SUBC Rm,Rn
        0x300A => {
            let dn = rc.read_write(a, n);
            let sm = if m == n { dn } else { rc.read(a, m) };
            let tr = rc.read_t(a);
            a.subs(W, ZR, ZR, tr);
            a.sbcs(W, dn, dn, sm);
            rc.set_t_flags(Cond::Lo);
        }
        // SUBV Rm,Rn
        0x300B => {
            let dn = rc.read_write(a, n);
            let sm = if m == n { dn } else { rc.read(a, m) };
            a.subs(W, dn, dn, sm);
            rc.set_t_flags(Cond::Vs);
        }
        0x2009 => {
            let dn = rc.read_write(a, n);
            let sm = if m == n { dn } else { rc.read(a, m) };
            a.and(W, dn, dn, sm);
        }
        0x200B => {
            let dn = rc.read_write(a, n);
            let sm = if m == n { dn } else { rc.read(a, m) };
            a.orr(W, dn, dn, sm);
        }
        0x200A => {
            let dn = rc.read_write(a, n);
            let sm = if m == n { dn } else { rc.read(a, m) };
            a.eor(W, dn, dn, sm);
        }
        // TST Rm,Rn
        0x2008 => {
            let rn_r = rc.read(a, n);
            let rm_r = if m == n { rn_r } else { rc.read(a, m) };
            a.tst(W, rn_r, rm_r);
            rc.set_t_flags(Cond::Eq);
        }
        0x3000 | 0x3002 | 0x3003 | 0x3006 | 0x3007 => {
            let rn_r = rc.read(a, n);
            let rm_r = if m == n { rn_r } else { rc.read(a, m) };
            a.cmp(W, rn_r, rm_r);
            let cond = match opcode & 0xF {
                0 => Cond::Eq,
                2 => Cond::Hs,
                3 => Cond::Ge,
                6 => Cond::Hi,
                _ => Cond::Gt,
            };
            rc.set_t_flags(cond);
        }
        // CMP/STR Rm,Rn: zero-byte test on (Rm ^ Rn)
        0x200C => {
            let rn_r = rc.read(a, n);
            let rm_r = if m == n { rn_r } else { rc.read(a, m) };
            a.eor(W, T0, rn_r, rm_r);
            a.mov_imm32(T1, 0x0101_0101);
            a.sub(W, T1, T0, T1);
            a.bic(W, T0, T1, T0);
            assert!(a.tst_imm(W, T0, 0x8080_8080));
            rc.set_t_flags(Cond::Ne);
        }
        // DIV0S Rm,Rn
        0x2007 => {
            let rm_r = rc.read(a, m);
            let rn_r = if m == n { rm_r } else { rc.read(a, n) };
            a.lsr_imm(W, T0, rm_r, 31);
            a.lsr_imm(W, T1, rn_r, 31);
            a.strb(T0, REGS, m_offset());
            a.strb(T1, REGS, q_offset());
            a.eor(W, T0, T0, T1);
            rc.set_t_reg(a, T0);
        }
        // DIV1 Rm,Rn
        0x3004 => {
            let dn = rc.read_write(a, n);
            let sm = if m == n { dn } else { rc.read(a, m) };
            let tr = rc.read_t(a);
            a.lsr_imm(W, T3, dn, 31);
            a.lsl_imm(W, T0, dn, 1);
            a.orr(W, T0, T0, tr);
            a.ldrb(T2, REGS, m_offset());
            a.ldrb(T4, REGS, q_offset());
            a.subs(W, T5, T0, sm);
            a.cset(W, T6, Cond::Lo);
            a.adds(W, T0, T0, sm);
            a.cset(W, T1, Cond::Hs);
            a.cmp(W, T4, T2);
            a.csel(W, dn, T5, T0, Cond::Eq);
            a.csel(W, T1, T6, T1, Cond::Eq);
            a.eor(W, T1, T1, T3);
            a.eor(W, T4, T1, T2);
            a.strb(T4, REGS, q_offset());
            a.cmp(W, T4, T2);
            rc.set_t_flags(Cond::Eq);
        }
        // MUL.L Rm,Rn
        0x0007 => {
            let sm = rc.read(a, m);
            let sn = if m == n { sm } else { rc.read(a, n) };
            a.mul(W, T0, sm, sn);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, macl)));
        }
        // MULU.W Rm,Rn
        0x200E => {
            let sm = rc.read(a, m);
            let sn = if m == n { sm } else { rc.read(a, n) };
            a.uxth(T0, sm);
            a.uxth(T1, sn);
            a.mul(W, T0, T0, T1);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, macl)));
        }
        // MULS.W Rm,Rn
        0x200F => {
            let sm = rc.read(a, m);
            let sn = if m == n { sm } else { rc.read(a, n) };
            a.sxth(W, T0, sm);
            a.sxth(W, T1, sn);
            a.mul(W, T0, T0, T1);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, macl)));
        }
        // DMULU.L Rm,Rn
        0x3005 => {
            let sm = rc.read(a, m);
            let sn = if m == n { sm } else { rc.read(a, n) };
            a.umull(T0, sm, sn);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, macl)));
            a.lsr_imm(X, T0, T0, 32);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, mach)));
        }
        // DMULS.L Rm,Rn
        0x300D => {
            let sm = rc.read(a, m);
            let sn = if m == n { sm } else { rc.read(a, n) };
            a.smull(T0, sm, sn);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, macl)));
            a.lsr_imm(X, T0, T0, 32);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, mach)));
        }
        0x600C | 0x600D | 0x600E | 0x600F | 0x6007 | 0x600B | 0x6009 => {
            let sm = rc.read(a, m);
            let dn = if m == n { rc.read_write(a, n) } else { rc.write_only(a, n) };
            match opcode & 0xF {
                0xC => a.uxtb(dn, sm),
                0xD => a.uxth(dn, sm),
                0xE => a.sxtb(W, dn, sm),
                0xF => a.sxth(W, dn, sm),
                0x7 => a.mvn(W, dn, sm),
                0xB => a.neg(W, dn, sm),
                _ => a.ror_imm(W, dn, sm, 16),
            }
        }
        // NEGC Rm,Rn
        0x600A => {
            let sm = rc.read(a, m);
            let dn = if m == n { rc.read_write(a, n) } else { rc.write_only(a, n) };
            let tr = rc.read_t(a);
            a.subs(W, ZR, ZR, tr);
            a.sbcs(W, dn, ZR, sm);
            rc.set_t_flags(Cond::Lo);
        }
        // SWAP.B: swap the low two bytes, keep the high half
        0x6008 => {
            let sm = rc.read(a, m);
            let dn = if m == n { rc.read_write(a, n) } else { rc.write_only(a, n) };
            a.rev16(W, T1, sm);
            if m != n {
                a.mov(W, dn, sm);
            }
            a.bfm(W, dn, T1, 0, 15);
        }
        // XTRCT Rm,Rn: (Rm << 16) | (Rn >> 16)
        0x200D => {
            let sm = rc.read(a, m);
            let dn = if m == n { sm } else { rc.read_write(a, n) };
            a.extr(W, dn, sm, dn, 16);
            if m == n {
                // `dn == sm` was already obtained via `read`; mark `n` dirty
                let _ = rc.read_write(a, n);
            }
        }
        _ => return translate_f0ff_cached(a, rc, opcode),
    }
    true
}

fn translate_f0ff_cached(a: &mut Asm, rc: &mut RegCache, opcode: u16) -> bool {
    let n = rn(opcode);
    match opcode & 0xF0FF {
        // CMP/PZ, CMP/PL
        0x4011 | 0x4015 => {
            let rn_r = rc.read(a, n);
            a.cmp_imm(W, rn_r, 0);
            rc.set_t_flags(if opcode & 0xFF == 0x11 { Cond::Ge } else { Cond::Gt });
        }
        // DT Rn
        0x4010 => {
            let dn = rc.read_write(a, n);
            a.subs_imm(W, dn, dn, 1);
            rc.set_t_flags(Cond::Eq);
        }
        // SHLL/SHAL: T = bit 31
        0x4000 | 0x4020 => {
            let dn = rc.read_write(a, n);
            a.lsr_imm(W, T1, dn, 31);
            a.lsl_imm(W, dn, dn, 1);
            rc.set_t_reg(a, T1);
        }
        // SHLR / SHAR: T = bit 0
        0x4001 | 0x4021 => {
            let dn = rc.read_write(a, n);
            assert!(a.and_imm(W, T1, dn, 1), "1 is a logical immediate");
            if opcode & 0xFF == 0x01 {
                a.lsr_imm(W, dn, dn, 1);
            } else {
                a.asr_imm(W, dn, dn, 1);
            }
            rc.set_t_reg(a, T1);
        }
        // ROTL: T = bit 31; ROTR: T = bit 0
        0x4004 => {
            let dn = rc.read_write(a, n);
            a.lsr_imm(W, T1, dn, 31);
            a.ror_imm(W, dn, dn, 31);
            rc.set_t_reg(a, T1);
        }
        0x4005 => {
            let dn = rc.read_write(a, n);
            assert!(a.and_imm(W, T1, dn, 1), "1 is a logical immediate");
            a.ror_imm(W, dn, dn, 1);
            rc.set_t_reg(a, T1);
        }
        // ROTCL: T = bit 31, Rn = (Rn << 1) | old_T
        0x4024 => {
            let dn = rc.read_write(a, n);
            let old_t = rc.read_t(a);
            a.lsr_imm(W, T2, dn, 31);
            a.lsl_imm(W, dn, dn, 1);
            a.orr(W, dn, dn, old_t);
            rc.set_t_reg(a, T2);
        }
        // ROTCR: T = bit 0, Rn = (Rn >> 1) | (old_T << 31)
        0x4025 => {
            let dn = rc.read_write(a, n);
            let old_t = rc.read_t(a);
            assert!(a.and_imm(W, T2, dn, 1), "1 is a logical immediate");
            a.lsr_imm(W, dn, dn, 1);
            a.lsl_imm(W, T1, old_t, 31);
            a.orr(W, dn, dn, T1);
            rc.set_t_reg(a, T2);
        }
        // STS MACH / MACL / PR, Rn
        0x000A => {
            let dn = rc.write_only(a, n);
            a.ldr(W, dn, REGS, reg_off(offset_of!(Sh2Registers, mach)));
        }
        0x001A => {
            let dn = rc.write_only(a, n);
            a.ldr(W, dn, REGS, reg_off(offset_of!(Sh2Registers, macl)));
        }
        0x002A => {
            let dn = rc.write_only(a, n);
            a.ldr(W, dn, REGS, reg_off(offset_of!(Sh2Registers, pr)));
        }
        // LDS Rm, MACH / MACL / PR (Rm encoded in bits 8..11)
        0x400A => {
            let sr = rc.read(a, n);
            a.str(W, sr, REGS, reg_off(offset_of!(Sh2Registers, mach)));
        }
        0x401A => {
            let sr = rc.read(a, n);
            a.str(W, sr, REGS, reg_off(offset_of!(Sh2Registers, macl)));
        }
        0x402A => {
            let sr = rc.read(a, n);
            a.str(W, sr, REGS, reg_off(offset_of!(Sh2Registers, pr)));
        }
        // STC GBR / VBR, Rn
        0x0012 => {
            let dn = rc.write_only(a, n);
            a.ldr(W, dn, REGS, reg_off(offset_of!(Sh2Registers, gbr)));
        }
        0x0022 => {
            let dn = rc.write_only(a, n);
            a.ldr(W, dn, REGS, reg_off(offset_of!(Sh2Registers, vbr)));
        }
        // STC SR, Rn
        0x0002 => {
            let tr = rc.read_t(a);
            a.ldrb(T1, REGS, s_offset());
            a.lsl_imm(W, T1, T1, 1);
            a.orr(W, T0, tr, T1);
            a.ldrb(T1, REGS, imask_offset());
            a.lsl_imm(W, T1, T1, 4);
            a.orr(W, T0, T0, T1);
            a.ldrb(T1, REGS, q_offset());
            a.lsl_imm(W, T1, T1, 8);
            a.orr(W, T0, T0, T1);
            a.ldrb(T1, REGS, m_offset());
            a.lsl_imm(W, T1, T1, 9);
            let dn = rc.write_only(a, n);
            a.orr(W, dn, T0, T1);
        }
        // LDC Rm, GBR / VBR (Rm encoded in bits 8..11)
        0x401E => {
            let sr = rc.read(a, n);
            a.str(W, sr, REGS, reg_off(offset_of!(Sh2Registers, gbr)));
        }
        0x402E => {
            let sr = rc.read(a, n);
            a.str(W, sr, REGS, reg_off(offset_of!(Sh2Registers, vbr)));
        }
        // LDC Rm, SR (Rm encoded in bits 8..11)
        0x400E => {
            let sr = rc.read(a, n);
            assert!(a.and_imm(W, T_REG, sr, 1));
            rc.t = TState::Reg { dirty: true };
            a.ubfm(W, T1, sr, 1, 1);
            a.strb(T1, REGS, s_offset());
            a.ubfm(W, T1, sr, 4, 7);
            a.strb(T1, REGS, imask_offset());
            a.ubfm(W, T1, sr, 8, 8);
            a.strb(T1, REGS, q_offset());
            a.ubfm(W, T1, sr, 9, 9);
            a.strb(T1, REGS, m_offset());
        }
        // SHLLn / SHLRn
        0x4008 | 0x4018 | 0x4028 | 0x4009 | 0x4019 | 0x4029 => {
            let amount = match (opcode >> 4) & 0xF {
                0 => 2,
                1 => 8,
                _ => 16,
            };
            let dn = rc.read_write(a, n);
            if opcode & 1 == 0 {
                a.lsl_imm(W, dn, dn, amount);
            } else {
                a.lsr_imm(W, dn, dn, amount);
            }
        }
        // MOVT Rn
        0x0029 => {
            let tr = rc.read_t(a);
            let dn = rc.write_only(a, n);
            a.mov(W, dn, tr);
        }
        _ => return translate_exact_cached(a, rc, opcode),
    }
    true
}

fn translate_exact_cached(a: &mut Asm, rc: &mut RegCache, opcode: u16) -> bool {
    match opcode {
        // NOP
        0x0009 => {}
        // CLRT / SETT
        0x0008 | 0x0018 => {
            a.movz(W, T_REG, u16::from(opcode == 0x0018), 0);
            rc.t = TState::Reg { dirty: true };
        }
        // DIV0U: M = Q = T = 0
        0x0019 => {
            a.strb(ZR, REGS, m_offset());
            a.strb(ZR, REGS, q_offset());
            a.movz(W, T_REG, 0, 0);
            rc.t = TState::Reg { dirty: true };
        }
        // CLRMAC: MACH = MACL = 0
        0x0028 => {
            a.str(W, ZR, REGS, reg_off(offset_of!(Sh2Registers, macl)));
            a.str(W, ZR, REGS, reg_off(offset_of!(Sh2Registers, mach)));
        }
        _ => return translate_imm_cached(a, rc, opcode),
    }
    true
}

fn translate_imm_cached(a: &mut Asm, rc: &mut RegCache, opcode: u16) -> bool {
    let n = rn(opcode);
    let simm = i32::from(opcode as u8 as i8);
    match opcode >> 12 {
        // MOV #imm,Rn
        0xE => {
            let dn = rc.write_only(a, n);
            a.mov_imm32(dn, simm as u32);
            return true;
        }
        // ADD #imm,Rn
        0x7 => {
            let dn = rc.read_write(a, n);
            if simm >= 0 {
                a.add_imm(W, dn, dn, simm as u32);
            } else {
                a.sub_imm(W, dn, dn, (-simm) as u32);
            }
            return true;
        }
        _ => {}
    }
    match opcode >> 8 {
        // CMP/EQ #imm,R0 (sign-extended)
        0x88 => {
            let r0 = rc.read(a, 0);
            if simm >= 0 {
                a.cmp_imm(W, r0, simm as u32);
            } else {
                a.mov_imm32(T1, simm as u32);
                a.cmp(W, r0, T1);
            }
            rc.set_t_flags(Cond::Eq);
        }
        // TST #imm,R0 (zero-extended)
        0xC8 => {
            let imm = u64::from(opcode & 0xFF);
            let r0 = rc.read(a, 0);
            if imm == 0 || !a.tst_imm(W, r0, imm) {
                a.mov_imm32(T1, imm as u32);
                a.tst(W, r0, T1);
            }
            rc.set_t_flags(Cond::Eq);
        }
        0xC9..=0xCB => {
            let imm = u64::from(opcode & 0xFF);
            let r0 = rc.read_write(a, 0);
            match opcode >> 8 {
                0xC9 => {
                    if imm == 0 || !a.and_imm(W, r0, r0, imm) {
                        a.mov_imm32(T1, imm as u32);
                        a.and(W, r0, r0, T1);
                    }
                }
                0xCA => {
                    if imm == 0 || !a.eor_imm(W, r0, r0, imm) {
                        a.mov_imm32(T1, imm as u32);
                        a.eor(W, r0, r0, T1);
                    }
                }
                _ => {
                    if imm == 0 || !a.orr_imm(W, r0, r0, imm) {
                        a.mov_imm32(T1, imm as u32);
                        a.orr(W, r0, r0, T1);
                    }
                }
            }
        }
        _ => return false,
    }
    true
}

/// Appends code for `opcode`; false if it is not a register-only instruction this translator knows
pub(crate) fn translate(a: &mut Asm, opcode: u16) -> bool {
    let n = rn(opcode);
    match opcode & 0xF00F {
        // MOV Rm,Rn
        0x6003 => {
            load(a, T0, rm(opcode));
            store(a, T0, n);
        }
        0x300C => binary(a, opcode, |a, d, x, y| a.add(W, d, x, y)),
        // ADDC Rm,Rn
        0x300E => {
            load(a, T0, n);
            load(a, T1, rm(opcode));
            a.ldrb(T2, REGS, t_offset());
            a.cmp_imm(W, T2, 1);
            a.adcs(W, T0, T0, T1);
            store(a, T0, n);
            store_t_cond(a, Cond::Hs);
        }
        // ADDV Rm,Rn
        0x300F => {
            load(a, T0, n);
            load(a, T1, rm(opcode));
            a.adds(W, T0, T0, T1);
            store(a, T0, n);
            store_t_cond(a, Cond::Vs);
        }
        0x3008 => binary(a, opcode, |a, d, x, y| a.sub(W, d, x, y)),
        // SUBC Rm,Rn
        0x300A => {
            load(a, T0, n);
            load(a, T1, rm(opcode));
            a.ldrb(T2, REGS, t_offset());
            a.subs(W, ZR, ZR, T2);
            a.sbcs(W, T0, T0, T1);
            store(a, T0, n);
            store_t_cond(a, Cond::Lo);
        }
        // SUBV Rm,Rn
        0x300B => {
            load(a, T0, n);
            load(a, T1, rm(opcode));
            a.subs(W, T0, T0, T1);
            store(a, T0, n);
            store_t_cond(a, Cond::Vs);
        }
        0x2009 => binary(a, opcode, |a, d, x, y| a.and(W, d, x, y)),
        0x200B => binary(a, opcode, |a, d, x, y| a.orr(W, d, x, y)),
        0x200A => binary(a, opcode, |a, d, x, y| a.eor(W, d, x, y)),
        // TST Rm,Rn
        0x2008 => {
            load(a, T0, n);
            load(a, T1, rm(opcode));
            a.tst(W, T0, T1);
            store_t_cond(a, Cond::Eq);
        }
        0x3000 => compare(a, opcode, Cond::Eq),
        0x3002 => compare(a, opcode, Cond::Hs),
        0x3003 => compare(a, opcode, Cond::Ge),
        0x3006 => compare(a, opcode, Cond::Hi),
        0x3007 => compare(a, opcode, Cond::Gt),
        // CMP/STR Rm,Rn: zero-byte test on (Rm ^ Rn)
        0x200C => {
            load(a, T0, n);
            load(a, T1, rm(opcode));
            a.eor(W, T0, T0, T1);
            a.mov_imm32(T1, 0x0101_0101);
            a.sub(W, T1, T0, T1);
            a.bic(W, T0, T1, T0);
            assert!(a.tst_imm(W, T0, 0x8080_8080));
            store_t_cond(a, Cond::Ne);
        }
        // DIV0S Rm,Rn
        0x2007 => {
            load(a, T0, rm(opcode));
            load(a, T1, n);
            a.lsr_imm(W, T0, T0, 31);
            a.lsr_imm(W, T1, T1, 31);
            a.strb(T0, REGS, m_offset());
            a.strb(T1, REGS, q_offset());
            a.eor(W, T0, T0, T1);
            store_t_reg(a, T0);
        }
        // DIV1 Rm,Rn
        0x3004 => {
            load(a, T0, n);
            load(a, T1, rm(opcode));
            a.ldrb(T2, REGS, t_offset());
            a.lsr_imm(W, T3, T0, 31);
            a.lsl_imm(W, T0, T0, 1);
            a.orr(W, T0, T0, T2);
            a.ldrb(T2, REGS, m_offset());
            a.ldrb(T4, REGS, q_offset());
            a.subs(W, T5, T0, T1);
            a.cset(W, T6, Cond::Lo);
            a.adds(W, T0, T0, T1);
            a.cset(W, T1, Cond::Hs);
            a.cmp(W, T4, T2);
            a.csel(W, T0, T5, T0, Cond::Eq);
            a.csel(W, T1, T6, T1, Cond::Eq);
            store(a, T0, n);
            a.eor(W, T1, T1, T3);
            a.eor(W, T4, T1, T2);
            a.strb(T4, REGS, q_offset());
            a.cmp(W, T4, T2);
            store_t_cond(a, Cond::Eq);
        }
        // MUL.L Rm,Rn
        0x0007 => {
            load(a, T0, rm(opcode));
            load(a, T1, n);
            a.mul(W, T0, T0, T1);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, macl)));
        }
        // MULU.W Rm,Rn
        0x200E => {
            load(a, T0, rm(opcode));
            a.uxth(T0, T0);
            load(a, T1, n);
            a.uxth(T1, T1);
            a.mul(W, T0, T0, T1);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, macl)));
        }
        // MULS.W Rm,Rn
        0x200F => {
            load(a, T0, rm(opcode));
            a.sxth(W, T0, T0);
            load(a, T1, n);
            a.sxth(W, T1, T1);
            a.mul(W, T0, T0, T1);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, macl)));
        }
        // DMULU.L Rm,Rn
        0x3005 => {
            load(a, T0, rm(opcode));
            load(a, T1, n);
            a.umull(T0, T0, T1);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, macl)));
            a.lsr_imm(X, T0, T0, 32);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, mach)));
        }
        // DMULS.L Rm,Rn
        0x300D => {
            load(a, T0, rm(opcode));
            load(a, T1, n);
            a.smull(T0, T0, T1);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, macl)));
            a.lsr_imm(X, T0, T0, 32);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, mach)));
        }
        0x600C => unary(a, opcode, |a, d, x| a.uxtb(d, x)),
        0x600D => unary(a, opcode, |a, d, x| a.uxth(d, x)),
        0x600E => unary(a, opcode, |a, d, x| a.sxtb(W, d, x)),
        0x600F => unary(a, opcode, |a, d, x| a.sxth(W, d, x)),
        0x6007 => unary(a, opcode, |a, d, x| a.mvn(W, d, x)),
        0x600B => unary(a, opcode, |a, d, x| a.neg(W, d, x)),
        // NEGC Rm,Rn
        0x600A => {
            load(a, T1, rm(opcode));
            a.ldrb(T2, REGS, t_offset());
            a.subs(W, ZR, ZR, T2);
            a.sbcs(W, T0, ZR, T1);
            store(a, T0, n);
            store_t_cond(a, Cond::Lo);
        }
        // SWAP.B: swap the low two bytes, keep the high half
        0x6008 => {
            load(a, T0, rm(opcode));
            a.rev16(W, T1, T0);
            a.bfm(W, T0, T1, 0, 15);
            store(a, T0, n);
        }
        // SWAP.W
        0x6009 => unary(a, opcode, |a, d, x| a.ror_imm(W, d, x, 16)),
        // XTRCT Rm,Rn: (Rm << 16) | (Rn >> 16)
        0x200D => {
            load(a, T0, rm(opcode));
            load(a, T1, n);
            a.extr(W, T0, T0, T1, 16);
            store(a, T0, n);
        }
        _ => return translate_f0ff(a, opcode),
    }
    true
}

fn translate_f0ff(a: &mut Asm, opcode: u16) -> bool {
    let n = rn(opcode);
    match opcode & 0xF0FF {
        // CMP/PZ, CMP/PL
        0x4011 | 0x4015 => {
            load(a, T0, n);
            a.cmp_imm(W, T0, 0);
            store_t_cond(a, if opcode & 0xFF == 0x11 { Cond::Ge } else { Cond::Gt });
        }
        // DT Rn
        0x4010 => {
            load(a, T0, n);
            a.subs_imm(W, T0, T0, 1);
            store(a, T0, n);
            store_t_cond(a, Cond::Eq);
        }
        // SHLL/SHAL: T = bit 31
        0x4000 | 0x4020 => {
            load(a, T0, n);
            a.lsr_imm(W, T1, T0, 31);
            a.lsl_imm(W, T0, T0, 1);
            store(a, T0, n);
            store_t_reg(a, T1);
        }
        // SHLR / SHAR: T = bit 0
        0x4001 | 0x4021 => {
            load(a, T0, n);
            assert!(a.and_imm(W, T1, T0, 1), "1 is a logical immediate");
            if opcode & 0xFF == 0x01 {
                a.lsr_imm(W, T0, T0, 1);
            } else {
                a.asr_imm(W, T0, T0, 1);
            }
            store(a, T0, n);
            store_t_reg(a, T1);
        }
        // ROTL: T = bit 31; ROTR: T = bit 0
        0x4004 => {
            load(a, T0, n);
            a.lsr_imm(W, T1, T0, 31);
            a.ror_imm(W, T0, T0, 31);
            store(a, T0, n);
            store_t_reg(a, T1);
        }
        0x4005 => {
            load(a, T0, n);
            assert!(a.and_imm(W, T1, T0, 1), "1 is a logical immediate");
            a.ror_imm(W, T0, T0, 1);
            store(a, T0, n);
            store_t_reg(a, T1);
        }
        // ROTCL: T = bit 31, Rn = (Rn << 1) | old_T
        0x4024 => {
            load(a, T0, n);
            a.ldrb(T1, REGS, t_offset());
            a.lsr_imm(W, T2, T0, 31);
            a.lsl_imm(W, T0, T0, 1);
            a.orr(W, T0, T0, T1);
            store(a, T0, n);
            store_t_reg(a, T2);
        }
        // ROTCR: T = bit 0, Rn = (Rn >> 1) | (old_T << 31)
        0x4025 => {
            load(a, T0, n);
            a.ldrb(T1, REGS, t_offset());
            assert!(a.and_imm(W, T2, T0, 1), "1 is a logical immediate");
            a.lsr_imm(W, T0, T0, 1);
            a.lsl_imm(W, T1, T1, 31);
            a.orr(W, T0, T0, T1);
            store(a, T0, n);
            store_t_reg(a, T2);
        }
        // STS MACH / MACL / PR, Rn
        0x000A => {
            a.ldr(W, T0, REGS, reg_off(offset_of!(Sh2Registers, mach)));
            store(a, T0, n);
        }
        0x001A => {
            a.ldr(W, T0, REGS, reg_off(offset_of!(Sh2Registers, macl)));
            store(a, T0, n);
        }
        0x002A => {
            a.ldr(W, T0, REGS, reg_off(offset_of!(Sh2Registers, pr)));
            store(a, T0, n);
        }
        // LDS Rm, MACH / MACL / PR (Rm encoded in bits 8..11)
        0x400A => {
            load(a, T0, n);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, mach)));
        }
        0x401A => {
            load(a, T0, n);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, macl)));
        }
        0x402A => {
            load(a, T0, n);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, pr)));
        }
        // STC GBR / VBR, Rn
        0x0012 => {
            a.ldr(W, T0, REGS, reg_off(offset_of!(Sh2Registers, gbr)));
            store(a, T0, n);
        }
        0x0022 => {
            a.ldr(W, T0, REGS, reg_off(offset_of!(Sh2Registers, vbr)));
            store(a, T0, n);
        }
        // STC SR, Rn
        0x0002 => {
            a.ldrb(T0, REGS, t_offset());
            a.ldrb(T1, REGS, s_offset());
            a.lsl_imm(W, T1, T1, 1);
            a.orr(W, T0, T0, T1);
            a.ldrb(T1, REGS, imask_offset());
            a.lsl_imm(W, T1, T1, 4);
            a.orr(W, T0, T0, T1);
            a.ldrb(T1, REGS, q_offset());
            a.lsl_imm(W, T1, T1, 8);
            a.orr(W, T0, T0, T1);
            a.ldrb(T1, REGS, m_offset());
            a.lsl_imm(W, T1, T1, 9);
            a.orr(W, T0, T0, T1);
            store(a, T0, n);
        }
        // LDC Rm, GBR / VBR (Rm encoded in bits 8..11)
        0x401E => {
            load(a, T0, n);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, gbr)));
        }
        0x402E => {
            load(a, T0, n);
            a.str(W, T0, REGS, reg_off(offset_of!(Sh2Registers, vbr)));
        }
        // LDC Rm, SR (Rm encoded in bits 8..11)
        0x400E => {
            load(a, T0, n);
            assert!(a.and_imm(W, T1, T0, 1));
            a.strb(T1, REGS, t_offset());
            a.ubfm(W, T1, T0, 1, 1);
            a.strb(T1, REGS, s_offset());
            a.ubfm(W, T1, T0, 4, 7);
            a.strb(T1, REGS, imask_offset());
            a.ubfm(W, T1, T0, 8, 8);
            a.strb(T1, REGS, q_offset());
            a.ubfm(W, T1, T0, 9, 9);
            a.strb(T1, REGS, m_offset());
        }
        // SHLLn / SHLRn
        0x4008 | 0x4018 | 0x4028 | 0x4009 | 0x4019 | 0x4029 => {
            let amount = match (opcode >> 4) & 0xF {
                0 => 2,
                1 => 8,
                _ => 16,
            };
            load(a, T0, n);
            if opcode & 1 == 0 {
                a.lsl_imm(W, T0, T0, amount);
            } else {
                a.lsr_imm(W, T0, T0, amount);
            }
            store(a, T0, n);
        }
        // MOVT Rn
        0x0029 => {
            a.ldrb(T0, REGS, t_offset());
            store(a, T0, n);
        }
        _ => return translate_exact(a, opcode),
    }
    true
}

fn translate_exact(a: &mut Asm, opcode: u16) -> bool {
    match opcode {
        // NOP
        0x0009 => {}
        // CLRT / SETT
        0x0008 | 0x0018 => {
            a.movz(W, T0, u16::from(opcode == 0x0018), 0);
            store_t_reg(a, T0);
        }
        // DIV0U: M = Q = T = 0
        0x0019 => {
            a.strb(ZR, REGS, m_offset());
            a.strb(ZR, REGS, q_offset());
            a.strb(ZR, REGS, t_offset());
        }
        // CLRMAC: MACH = MACL = 0
        0x0028 => {
            a.str(W, ZR, REGS, reg_off(offset_of!(Sh2Registers, macl)));
            a.str(W, ZR, REGS, reg_off(offset_of!(Sh2Registers, mach)));
        }
        _ => return translate_imm(a, opcode),
    }
    true
}

fn translate_imm(a: &mut Asm, opcode: u16) -> bool {
    let n = rn(opcode);
    let simm = i32::from(opcode as u8 as i8);
    match opcode >> 12 {
        // MOV #imm,Rn
        0xE => {
            a.mov_imm32(T0, simm as u32);
            store(a, T0, n);
            return true;
        }
        // ADD #imm,Rn
        0x7 => {
            load(a, T0, n);
            if simm >= 0 {
                a.add_imm(W, T0, T0, simm as u32);
            } else {
                a.sub_imm(W, T0, T0, (-simm) as u32);
            }
            store(a, T0, n);
            return true;
        }
        _ => {}
    }
    match opcode >> 8 {
        // CMP/EQ #imm,R0 (sign-extended)
        0x88 => {
            load(a, T0, 0);
            a.mov_imm32(T1, simm as u32);
            a.cmp(W, T0, T1);
            store_t_cond(a, Cond::Eq);
        }
        // TST #imm,R0 (zero-extended)
        0xC8 => {
            let imm = u64::from(opcode & 0xFF);
            load(a, T0, 0);
            if imm == 0 || !a.tst_imm(W, T0, imm) {
                a.mov_imm32(T1, imm as u32);
                a.tst(W, T0, T1);
            }
            store_t_cond(a, Cond::Eq);
        }
        0xC9 => logical_imm_r0(a, opcode, |a, d, x, v| a.and_imm(W, d, x, v), |a, d, x, y| a.and(W, d, x, y)),
        0xCA => logical_imm_r0(a, opcode, |a, d, x, v| a.eor_imm(W, d, x, v), |a, d, x, y| a.eor(W, d, x, y)),
        0xCB => logical_imm_r0(a, opcode, |a, d, x, v| a.orr_imm(W, d, x, v), |a, d, x, y| a.orr(W, d, x, y)),
        _ => return false,
    }
    true
}

#[cfg(test)]
mod tests {
    use super::super::a64_sim::Sim;
    use super::*;
    use crate::Sh2;

    struct Rng(u64);
    impl Rng {
        fn next(&mut self) -> u64 {
            self.0 ^= self.0 << 13;
            self.0 ^= self.0 >> 7;
            self.0 ^= self.0 << 17;
            self.0
        }
    }

    // (pattern, operand bits)
    const RR: u16 = 0x0FF0;
    const RN: u16 = 0x0F00;
    const IMM8: u16 = 0x00FF;
    const RN_IMM8: u16 = 0x0FFF;
    const PATTERNS: &[(u16, u16)] = &[
        (0x6003, RR), (0x300C, RR), (0x300E, RR), (0x300F, RR), (0x3008, RR), (0x300A, RR),
        (0x300B, RR), (0x2009, RR), (0x200B, RR), (0x200A, RR), (0x2008, RR), (0x3000, RR),
        (0x3002, RR), (0x3003, RR), (0x3006, RR), (0x3007, RR), (0x200C, RR), (0x2007, RR),
        (0x3004, RR), (0x0007, RR), (0x200E, RR), (0x200F, RR), (0x3005, RR), (0x300D, RR),
        (0x600C, RR), (0x600D, RR), (0x600E, RR), (0x600F, RR), (0x6007, RR), (0x600B, RR),
        (0x600A, RR), (0x6008, RR), (0x6009, RR), (0x200D, RR), (0x4011, RN), (0x4015, RN),
        (0x4010, RN), (0x4000, RN), (0x4020, RN), (0x4001, RN), (0x4021, RN), (0x4004, RN),
        (0x4005, RN), (0x4024, RN), (0x4025, RN), (0x4008, RN), (0x4018, RN), (0x4028, RN),
        (0x4009, RN), (0x4019, RN), (0x4029, RN), (0x0029, RN), (0x000A, RN), (0x001A, RN),
        (0x002A, RN), (0x400A, RN), (0x401A, RN), (0x402A, RN), (0x0002, RN), (0x0012, RN),
        (0x0022, RN), (0x400E, RN), (0x401E, RN), (0x402E, RN), (0x0009, 0), (0x0008, 0),
        (0x0018, 0), (0x0019, 0), (0x0028, 0), (0xE000, RN_IMM8), (0x7000, RN_IMM8),
        (0x8800, IMM8), (0xC800, IMM8), (0xC900, IMM8), (0xCA00, IMM8), (0xCB00, IMM8),
    ];

    fn interesting(rng: &mut Rng) -> u32 {
        match rng.next() % 6 {
            0 => 0,
            1 => u32::MAX,
            2 => 0x8000_0000,
            3 => (rng.next() % 512) as u32,
            4 => (rng.next() as u32) | 0x8000_0000,
            _ => rng.next() as u32,
        }
    }

    /// Every translated instruction, with random operands, registers and T, leaves exactly the
    /// register file the interpreter leaves
    #[test]
    fn translated_register_ops_match_interpreter() {
        let mut rng = Rng(0x1234_5678_9ABC_DEF1);
        let table = crate::jit::form::tests::interpret;
        let mut checked = 0;
        for &(pattern, operand_bits) in PATTERNS {
            for _ in 0..400 {
                let operands = (rng.next() as u16) & operand_bits;
                let opcode = pattern | operands;

                let mut reference = Sh2::new("translate-test".into());
                for r in 0..16 {
                    reference.registers.gpr[r] = interesting(&mut rng);
                }
                reference.registers.sr.t = rng.next() & 1 != 0;
                reference.registers.sr.s = rng.next() & 1 != 0;
                reference.registers.sr.q = rng.next() & 1 != 0;
                reference.registers.sr.m = rng.next() & 1 != 0;
                reference.registers.sr.interrupt_mask = (rng.next() & 0xF) as u8;
                reference.registers.mach = interesting(&mut rng);
                reference.registers.macl = interesting(&mut rng);
                reference.registers.pr = interesting(&mut rng);
                reference.registers.gbr = interesting(&mut rng);
                reference.registers.vbr = interesting(&mut rng);
                let mut subject = reference.clone();

                table(&mut reference, opcode);

                let mut asm = Asm::new();
                assert!(translate(&mut asm, opcode), "{opcode:04X} not translated");
                asm.ret();
                let code = asm.finish();
                let mut sim = Sim::new();
                // SAFETY: the code only accesses `Sh2Registers` fields through x19 (checked by the
                // simulator's bounds)
                unsafe {
                    sim.run(&code, std::ptr::from_mut(&mut subject.registers).cast(), size_of::<Sh2Registers>());
                }
                assert_eq!(
                    subject.registers, reference.registers,
                    "{opcode:04X}: translated code differs from the interpreter"
                );
                checked += 1;
            }
        }
        assert_eq!(checked, PATTERNS.len() * 400);
    }

    #[test]
    fn cached_register_sequences_match_interpreter() {
        let mut rng = Rng(0xDEAD_BEEF_CAFE_BABEu64);
        let table = crate::jit::form::tests::interpret;
        // Include m == n aliasing cases plus multi-op chains using T/GPR reuse
        for _round in 0..800 {
            let mut reference = Sh2::new("cached-seq-test".into());
            for r in 0..16 {
                reference.registers.gpr[r] = interesting(&mut rng);
            }
            reference.registers.sr.t = rng.next() & 1 != 0;
            reference.registers.sr.s = rng.next() & 1 != 0;
            reference.registers.sr.q = rng.next() & 1 != 0;
            reference.registers.sr.m = rng.next() & 1 != 0;
            reference.registers.sr.interrupt_mask = (rng.next() & 0xF) as u8;
            reference.registers.mach = interesting(&mut rng);
            reference.registers.macl = interesting(&mut rng);
            reference.registers.pr = interesting(&mut rng);
            reference.registers.gbr = interesting(&mut rng);
            reference.registers.vbr = interesting(&mut rng);
            let mut subject = reference.clone();

            let mut asm = Asm::new();
            let mut rc = RegCache::new();
            let mut opcodes = Vec::with_capacity(14);
            for step in 0..14 {
                let &(pattern, operand_bits) = &PATTERNS[(rng.next() as usize) % PATTERNS.len()];
                let mut operands = (rng.next() as u16) & operand_bits;
                if step % 4 == 0 && operand_bits == RR {
                    // Force m == n aliasing frequently
                    let nib = (rng.next() & 0xF) as u16;
                    operands = (nib << 8) | (nib << 4);
                }
                let opcode = pattern | operands;
                opcodes.push(opcode);
                table(&mut reference, opcode);
                assert!(translate_cached(&mut asm, &mut rc, opcode), "{opcode:04X} not translated");
            }
            rc.flush_and_clear(&mut asm);
            asm.ret();
            let code = asm.finish();
            let mut sim = Sim::new();
            unsafe {
                sim.run(&code, std::ptr::from_mut(&mut subject.registers).cast(), size_of::<Sh2Registers>());
            }
            assert_eq!(
                subject.registers, reference.registers,
                "cached sequence {opcodes:04X?} differs from interpreter"
            );
        }
    }

    #[test]
    fn unknown_instructions_are_not_translated() {
        let mut asm = Asm::new();
        for opcode in [0x6012u16, 0xA000, 0x8900, 0x000B, 0xD123, 0xC500, 0x400B] {
            assert!(!translate(&mut asm, opcode), "{opcode:04X}");
        }
        assert!(asm.finish().is_empty());
    }
}
