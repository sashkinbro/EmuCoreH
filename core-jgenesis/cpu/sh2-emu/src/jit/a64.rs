//! Minimal AArch64 instruction encoder for the SH-2 block compiler (phase b).
//!
//! Host-testable: it only produces instruction words; nothing here maps or executes memory. The
//! encodings are checked against the LLVM assembler in the unit tests below. Only the subset the
//! block compiler needs is provided: 32-bit ALU work on guest registers, 64-bit pointer
//! arithmetic on the context, loads/stores with unsigned offsets, flag materialization and the
//! branches used for exits, side exits and call-outs.

#![allow(dead_code)]

/// A general-purpose register number (0-30); 31 is `wzr`/`xzr` or `sp` depending on the
/// instruction, exactly as in the architecture
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct R(pub(crate) u8);

pub(crate) const ZR: R = R(31);
pub(crate) const SP: R = R(31);
/// Link register
pub(crate) const LR: R = R(30);

/// Operand size: `W` = 32-bit (`sf = 0`), `X` = 64-bit (`sf = 1`)
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Size {
    W,
    X,
}

impl Size {
    fn sf(self) -> u32 {
        match self {
            Self::W => 0,
            Self::X => 1 << 31,
        }
    }

    fn bits(self) -> u32 {
        match self {
            Self::W => 32,
            Self::X => 64,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub(crate) enum Cond {
    Eq = 0,
    Ne = 1,
    Hs = 2,
    Lo = 3,
    Mi = 4,
    Pl = 5,
    Vs = 6,
    Vc = 7,
    Hi = 8,
    Ls = 9,
    Ge = 10,
    Lt = 11,
    Gt = 12,
    Le = 13,
    Al = 14,
}

impl Cond {
    pub(crate) fn invert(self) -> Self {
        // SAFETY: flipping bit 0 of 0-13 stays in 0-13
        unsafe { std::mem::transmute((self as u8) ^ 1) }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Shift {
    Lsl = 0,
    Lsr = 1,
    Asr = 2,
    Ror = 3,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Label(usize);

#[derive(Clone, Copy, Debug)]
enum FixKind {
    /// imm26 (B, BL)
    B26,
    /// imm19 at bit 5 (B.cond, CBZ, CBNZ, LDR literal)
    B19,
    /// imm14 at bit 5 (TBZ, TBNZ)
    B14,
}

#[derive(Default)]
pub(crate) struct Asm {
    pub(crate) code: Vec<u32>,
    cold: Vec<u32>,
    in_cold: bool,
    labels: Vec<Option<(bool, usize)>>,
    fixups: Vec<(bool, usize, Label, FixKind)>,
    abs_fixups: Vec<(bool, usize, usize, bool)>,
}

/// Encodes `value` as an AArch64 logical immediate `(N, immr, imms)` for an operand of `size`,
/// if it is one (a rotated run of ones replicated across the register)
pub(crate) fn logical_imm(value: u64, size: Size) -> Option<(u32, u32, u32)> {
    let width = size.bits();
    let value = if width == 32 {
        let v = value & 0xFFFF_FFFF;
        v | (v << 32)
    } else {
        value
    };
    if value == 0 || value == u64::MAX {
        return None;
    }
    // Smallest element size whose replication gives the value
    let mut esize = 64u32;
    while esize > 2 {
        let half = esize / 2;
        let mask = (1u64 << half) - 1;
        if (value & mask) != ((value >> half) & mask) {
            break;
        }
        esize = half;
    }
    let emask = if esize == 64 { u64::MAX } else { (1u64 << esize) - 1 };
    let elem = value & emask;
    // Rotate the element so that its run of ones starts at bit 0
    let ones = elem.count_ones();
    let rotate = (0..esize).find(|&r| {
        let rotated = if r == 0 { elem } else { ((elem >> r) | (elem << (esize - r))) & emask };
        rotated == (1u64 << ones) - 1
    })?;
    // `rotate` right-rotations normalize; the instruction rotates right by immr
    let immr = (esize - rotate) % esize;
    let n = u32::from(esize == 64);
    let imms = ((!(esize * 2 - 1)) & 0x3F) | (ones - 1);
    if size == Size::W && n == 1 {
        return None;
    }
    Some((n, immr, imms & 0x3F))
}

impl Asm {
    pub(crate) fn new() -> Self {
        Self::default()
    }

    pub(crate) fn set_cold(&mut self, cold: bool) {
        self.in_cold = cold;
    }

    pub(crate) fn hot_len(&self) -> usize {
        self.code.len()
    }

    pub(crate) fn cold_len(&self) -> usize {
        self.cold.len()
    }

    pub(crate) fn emit(&mut self, word: u32) {
        if self.in_cold {
            self.cold.push(word);
        } else {
            self.code.push(word);
        }
    }

    pub(crate) fn pos(&self) -> usize {
        if self.in_cold {
            self.cold.len()
        } else {
            self.code.len()
        }
    }

    pub(crate) fn new_label(&mut self) -> Label {
        self.labels.push(None);
        Label(self.labels.len() - 1)
    }

    pub(crate) fn bind(&mut self, label: Label) {
        assert!(self.labels[label.0].is_none(), "label bound twice");
        self.labels[label.0] = Some((self.in_cold, self.pos()));
    }

    /// Resolves every branch to a label; panics if split code or deferred calls were emitted.
    pub(crate) fn finish(self) -> Vec<u32> {
        assert!(self.cold.is_empty() && self.abs_fixups.is_empty(), "use finish_split");
        self.finish_split(0, 0).0
    }

    /// Resolves every relative and deferred absolute branch with `hot_base` and `cold_base`.
    pub(crate) fn finish_split(mut self, hot_base: usize, cold_base: usize) -> (Vec<u32>, Vec<u32>) {
        for &(src_cold, at, label, kind) in &self.fixups {
            let (dst_cold, target) = self.labels[label.0].expect("unbound label");
            let site_addr = (if src_cold { cold_base } else { hot_base }) + at * 4;
            let target_addr = (if dst_cold { cold_base } else { hot_base }) + target * 4;
            let delta = (target_addr as i64 - site_addr as i64) >> 2;
            let word = if src_cold { &mut self.cold[at] } else { &mut self.code[at] };
            match kind {
                FixKind::B26 => {
                    assert!((-(1 << 25)..(1 << 25)).contains(&delta), "branch out of range");
                    *word |= (delta as u32) & 0x03FF_FFFF;
                }
                FixKind::B19 => {
                    assert!((-(1 << 18)..(1 << 18)).contains(&delta), "branch out of range");
                    *word |= ((delta as u32) & 0x7FFFF) << 5;
                }
                FixKind::B14 => {
                    assert!((-(1 << 13)..(1 << 13)).contains(&delta), "branch out of range");
                    *word |= ((delta as u32) & 0x3FFF) << 5;
                }
            }
        }
        for &(src_cold, at, target_addr, is_bl) in &self.abs_fixups {
            let site_addr = (if src_cold { cold_base } else { hot_base }) + at * 4;
            let delta = (target_addr as isize - site_addr as isize) >> 2;
            assert!(
                (-(1 << 25)..(1 << 25)).contains(&delta),
                "abs branch target {target_addr:#x} out of +-128MB range from {site_addr:#x}"
            );
            let word = if src_cold { &mut self.cold[at] } else { &mut self.code[at] };
            let op = if is_bl { 0x9400_0000 } else { 0x1400_0000 };
            *word = op | ((delta as u32) & 0x03FF_FFFF);
        }
        self.fixups.clear();
        self.abs_fixups.clear();
        (std::mem::take(&mut self.code), std::mem::take(&mut self.cold))
    }

    // ---- Moves ----

    pub(crate) fn movz(&mut self, size: Size, rd: R, imm16: u16, shift: u32) {
        debug_assert!(shift % 16 == 0 && shift < size.bits());
        self.emit(size.sf() | 0x5280_0000 | ((shift / 16) << 21) | (u32::from(imm16) << 5) | u32::from(rd.0));
    }

    pub(crate) fn movk(&mut self, size: Size, rd: R, imm16: u16, shift: u32) {
        debug_assert!(shift % 16 == 0 && shift < size.bits());
        self.emit(size.sf() | 0x7280_0000 | ((shift / 16) << 21) | (u32::from(imm16) << 5) | u32::from(rd.0));
    }

    pub(crate) fn movn(&mut self, size: Size, rd: R, imm16: u16, shift: u32) {
        debug_assert!(shift % 16 == 0 && shift < size.bits());
        self.emit(size.sf() | 0x1280_0000 | ((shift / 16) << 21) | (u32::from(imm16) << 5) | u32::from(rd.0));
    }

    /// Loads a 32-bit constant in one or two instructions
    pub(crate) fn mov_imm32(&mut self, rd: R, value: u32) {
        let (lo, hi) = (value as u16, (value >> 16) as u16);
        if hi == 0 {
            self.movz(Size::W, rd, lo, 0);
        } else if lo == 0 {
            self.movz(Size::W, rd, hi, 16);
        } else if hi == 0xFFFF {
            self.movn(Size::W, rd, !lo, 0);
        } else if lo == 0xFFFF {
            self.movn(Size::W, rd, !hi, 16);
        } else if let Some((n, immr, imms)) = logical_imm(value.into(), Size::W) {
            // ORR rd, wzr, #imm
            self.emit(0x3200_0000 | (n << 22) | (immr << 16) | (imms << 10) | (31 << 5) | u32::from(rd.0));
        } else {
            self.movz(Size::W, rd, lo, 0);
            self.movk(Size::W, rd, hi, 16);
        }
    }

    /// `mov rd, rm` (ORR with the zero register)
    pub(crate) fn mov(&mut self, size: Size, rd: R, rm: R) {
        self.orr(size, rd, ZR, rm);
    }

    // ---- Arithmetic ----

    fn addsub_imm(&mut self, base: u32, size: Size, rd: R, rn: R, imm12: u32, shift12: bool) {
        assert!(imm12 < 4096, "add/sub immediate out of range");
        self.emit(
            size.sf() | base | (u32::from(shift12) << 22) | (imm12 << 10) | (u32::from(rn.0) << 5) | u32::from(rd.0),
        );
    }

    pub(crate) fn add_imm(&mut self, size: Size, rd: R, rn: R, imm12: u32) {
        self.addsub_imm(0x1100_0000, size, rd, rn, imm12, false);
    }

    pub(crate) fn adds_imm(&mut self, size: Size, rd: R, rn: R, imm12: u32) {
        self.addsub_imm(0x3100_0000, size, rd, rn, imm12, false);
    }

    pub(crate) fn sub_imm(&mut self, size: Size, rd: R, rn: R, imm12: u32) {
        self.addsub_imm(0x5100_0000, size, rd, rn, imm12, false);
    }

    pub(crate) fn subs_imm(&mut self, size: Size, rd: R, rn: R, imm12: u32) {
        self.addsub_imm(0x7100_0000, size, rd, rn, imm12, false);
    }

    pub(crate) fn cmp_imm(&mut self, size: Size, rn: R, imm12: u32) {
        self.subs_imm(size, ZR, rn, imm12);
    }

    fn addsub_reg(&mut self, base: u32, size: Size, rd: R, rn: R, rm: R, shift: Shift, amount: u32) {
        assert!(amount < size.bits() && shift != Shift::Ror);
        self.emit(
            size.sf()
                | base
                | ((shift as u32) << 22)
                | (u32::from(rm.0) << 16)
                | (amount << 10)
                | (u32::from(rn.0) << 5)
                | u32::from(rd.0),
        );
    }

    pub(crate) fn add(&mut self, size: Size, rd: R, rn: R, rm: R) {
        self.addsub_reg(0x0B00_0000, size, rd, rn, rm, Shift::Lsl, 0);
    }

    pub(crate) fn add_shifted(&mut self, size: Size, rd: R, rn: R, rm: R, shift: Shift, amount: u32) {
        self.addsub_reg(0x0B00_0000, size, rd, rn, rm, shift, amount);
    }

    pub(crate) fn adds(&mut self, size: Size, rd: R, rn: R, rm: R) {
        self.addsub_reg(0x2B00_0000, size, rd, rn, rm, Shift::Lsl, 0);
    }

    pub(crate) fn sub(&mut self, size: Size, rd: R, rn: R, rm: R) {
        self.addsub_reg(0x4B00_0000, size, rd, rn, rm, Shift::Lsl, 0);
    }

    pub(crate) fn subs(&mut self, size: Size, rd: R, rn: R, rm: R) {
        self.addsub_reg(0x6B00_0000, size, rd, rn, rm, Shift::Lsl, 0);
    }

    pub(crate) fn cmp(&mut self, size: Size, rn: R, rm: R) {
        self.subs(size, ZR, rn, rm);
    }

    pub(crate) fn neg(&mut self, size: Size, rd: R, rm: R) {
        self.sub(size, rd, ZR, rm);
    }

    /// Add with carry (C flag)
    pub(crate) fn adc(&mut self, size: Size, rd: R, rn: R, rm: R) {
        self.emit(size.sf() | 0x1A00_0000 | (u32::from(rm.0) << 16) | (u32::from(rn.0) << 5) | u32::from(rd.0));
    }

    pub(crate) fn adcs(&mut self, size: Size, rd: R, rn: R, rm: R) {
        self.emit(size.sf() | 0x3A00_0000 | (u32::from(rm.0) << 16) | (u32::from(rn.0) << 5) | u32::from(rd.0));
    }

    pub(crate) fn sbcs(&mut self, size: Size, rd: R, rn: R, rm: R) {
        self.emit(size.sf() | 0x7A00_0000 | (u32::from(rm.0) << 16) | (u32::from(rn.0) << 5) | u32::from(rd.0));
    }

    // ---- Logical ----

    fn logical_reg(&mut self, base: u32, size: Size, rd: R, rn: R, rm: R) {
        self.emit(size.sf() | base | (u32::from(rm.0) << 16) | (u32::from(rn.0) << 5) | u32::from(rd.0));
    }

    pub(crate) fn and(&mut self, size: Size, rd: R, rn: R, rm: R) {
        self.logical_reg(0x0A00_0000, size, rd, rn, rm);
    }

    pub(crate) fn orr(&mut self, size: Size, rd: R, rn: R, rm: R) {
        self.logical_reg(0x2A00_0000, size, rd, rn, rm);
    }

    pub(crate) fn orr_shifted(&mut self, size: Size, rd: R, rn: R, rm: R, shift: Shift, amount: u32) {
        assert!(amount < size.bits());
        self.emit(
            size.sf()
                | 0x2A00_0000
                | ((shift as u32) << 22)
                | (u32::from(rm.0) << 16)
                | (amount << 10)
                | (u32::from(rn.0) << 5)
                | u32::from(rd.0),
        );
    }

    pub(crate) fn eor(&mut self, size: Size, rd: R, rn: R, rm: R) {
        self.logical_reg(0x4A00_0000, size, rd, rn, rm);
    }

    pub(crate) fn bic(&mut self, size: Size, rd: R, rn: R, rm: R) {
        self.logical_reg(0x0A20_0000, size, rd, rn, rm);
    }

    pub(crate) fn ands(&mut self, size: Size, rd: R, rn: R, rm: R) {
        self.logical_reg(0x6A00_0000, size, rd, rn, rm);
    }

    pub(crate) fn tst(&mut self, size: Size, rn: R, rm: R) {
        self.ands(size, ZR, rn, rm);
    }

    /// `mvn rd, rm` (ORN with the zero register)
    pub(crate) fn mvn(&mut self, size: Size, rd: R, rm: R) {
        self.logical_reg(0x2A20_0000, size, rd, ZR, rm);
    }

    fn logical_imm_op(&mut self, base: u32, size: Size, rd: R, rn: R, value: u64) -> bool {
        let Some((n, immr, imms)) = logical_imm(value, size) else { return false };
        self.emit(
            size.sf() | base | (n << 22) | (immr << 16) | (imms << 10) | (u32::from(rn.0) << 5) | u32::from(rd.0),
        );
        true
    }

    /// Returns false (and emits nothing) when `value` is not a logical immediate
    #[must_use]
    pub(crate) fn and_imm(&mut self, size: Size, rd: R, rn: R, value: u64) -> bool {
        self.logical_imm_op(0x1200_0000, size, rd, rn, value)
    }

    #[must_use]
    pub(crate) fn orr_imm(&mut self, size: Size, rd: R, rn: R, value: u64) -> bool {
        self.logical_imm_op(0x3200_0000, size, rd, rn, value)
    }

    #[must_use]
    pub(crate) fn eor_imm(&mut self, size: Size, rd: R, rn: R, value: u64) -> bool {
        self.logical_imm_op(0x5200_0000, size, rd, rn, value)
    }

    #[must_use]
    pub(crate) fn tst_imm(&mut self, size: Size, rn: R, value: u64) -> bool {
        self.logical_imm_op(0x7200_0000, size, ZR, rn, value)
    }

    // ---- Bitfield, shifts, extends ----

    fn bitfield(&mut self, base: u32, size: Size, rd: R, rn: R, immr: u32, imms: u32) {
        let n = if size == Size::X { 1 << 22 } else { 0 };
        self.emit(size.sf() | base | n | (immr << 16) | (imms << 10) | (u32::from(rn.0) << 5) | u32::from(rd.0));
    }

    pub(crate) fn ubfm(&mut self, size: Size, rd: R, rn: R, immr: u32, imms: u32) {
        self.bitfield(0x5300_0000, size, rd, rn, immr, imms);
    }

    pub(crate) fn sbfm(&mut self, size: Size, rd: R, rn: R, immr: u32, imms: u32) {
        self.bitfield(0x1300_0000, size, rd, rn, immr, imms);
    }

    pub(crate) fn bfm(&mut self, size: Size, rd: R, rn: R, immr: u32, imms: u32) {
        self.bitfield(0x3300_0000, size, rd, rn, immr, imms);
    }

    pub(crate) fn lsl_imm(&mut self, size: Size, rd: R, rn: R, amount: u32) {
        let bits = size.bits();
        self.ubfm(size, rd, rn, (bits - amount) % bits, bits - 1 - amount);
    }

    pub(crate) fn lsr_imm(&mut self, size: Size, rd: R, rn: R, amount: u32) {
        self.ubfm(size, rd, rn, amount, size.bits() - 1);
    }

    pub(crate) fn asr_imm(&mut self, size: Size, rd: R, rn: R, amount: u32) {
        self.sbfm(size, rd, rn, amount, size.bits() - 1);
    }

    /// `ror rd, rn, #amount` (EXTR rd, rn, rn, #amount)
    pub(crate) fn ror_imm(&mut self, size: Size, rd: R, rn: R, amount: u32) {
        let n = if size == Size::X { 1 << 22 } else { 0 };
        self.emit(
            size.sf() | 0x1380_0000 | n | (u32::from(rn.0) << 16) | (amount << 10) | (u32::from(rn.0) << 5) | u32::from(rd.0),
        );
    }

    /// `extr rd, rn, rm, #lsb`: `(rn:rm) >> lsb`
    pub(crate) fn extr(&mut self, size: Size, rd: R, rn: R, rm: R, lsb: u32) {
        assert!(lsb < size.bits());
        let n = if size == Size::X { 1 << 22 } else { 0 };
        self.emit(
            size.sf() | 0x1380_0000 | n | (u32::from(rm.0) << 16) | (lsb << 10) | (u32::from(rn.0) << 5) | u32::from(rd.0),
        );
    }

    pub(crate) fn uxtb(&mut self, rd: R, rn: R) {
        self.ubfm(Size::W, rd, rn, 0, 7);
    }

    pub(crate) fn uxth(&mut self, rd: R, rn: R) {
        self.ubfm(Size::W, rd, rn, 0, 15);
    }

    pub(crate) fn sxtb(&mut self, size: Size, rd: R, rn: R) {
        self.sbfm(size, rd, rn, 0, 7);
    }

    pub(crate) fn sxth(&mut self, size: Size, rd: R, rn: R) {
        self.sbfm(size, rd, rn, 0, 15);
    }

    /// `sxtw xd, wn`
    pub(crate) fn sxtw(&mut self, rd: R, rn: R) {
        self.sbfm(Size::X, rd, rn, 0, 31);
    }

    /// Byte-reverse each halfword (`REV16`)
    pub(crate) fn rev16(&mut self, size: Size, rd: R, rn: R) {
        self.emit(size.sf() | 0x5AC0_0400 | (u32::from(rn.0) << 5) | u32::from(rd.0));
    }

    // ---- Conditional select / flags ----

    pub(crate) fn csel(&mut self, size: Size, rd: R, rn: R, rm: R, cond: Cond) {
        self.emit(
            size.sf() | 0x1A80_0000 | (u32::from(rm.0) << 16) | ((cond as u32) << 12) | (u32::from(rn.0) << 5) | u32::from(rd.0),
        );
    }

    pub(crate) fn csinc(&mut self, size: Size, rd: R, rn: R, rm: R, cond: Cond) {
        self.emit(
            size.sf()
                | 0x1A80_0400
                | (u32::from(rm.0) << 16)
                | ((cond as u32) << 12)
                | (u32::from(rn.0) << 5)
                | u32::from(rd.0),
        );
    }

    pub(crate) fn csinv(&mut self, size: Size, rd: R, rn: R, rm: R, cond: Cond) {
        self.emit(
            size.sf()
                | 0x5A80_0000
                | (u32::from(rm.0) << 16)
                | ((cond as u32) << 12)
                | (u32::from(rn.0) << 5)
                | u32::from(rd.0),
        );
    }

    /// `cset rd, cond` (CSINC rd, zr, zr, !cond)
    pub(crate) fn cset(&mut self, size: Size, rd: R, cond: Cond) {
        self.csinc(size, rd, ZR, ZR, cond.invert());
    }

    // ---- Multiply ----

    pub(crate) fn madd(&mut self, size: Size, rd: R, rn: R, rm: R, ra: R) {
        self.emit(
            size.sf() | 0x1B00_0000 | (u32::from(rm.0) << 16) | (u32::from(ra.0) << 10) | (u32::from(rn.0) << 5) | u32::from(rd.0),
        );
    }

    pub(crate) fn mul(&mut self, size: Size, rd: R, rn: R, rm: R) {
        self.madd(size, rd, rn, rm, ZR);
    }

    /// `smull xd, wn, wm`
    pub(crate) fn smull(&mut self, rd: R, rn: R, rm: R) {
        self.emit(0x9B20_7C00 | (u32::from(rm.0) << 16) | (u32::from(rn.0) << 5) | u32::from(rd.0));
    }

    /// `umull xd, wn, wm`
    pub(crate) fn umull(&mut self, rd: R, rn: R, rm: R) {
        self.emit(0x9BA0_7C00 | (u32::from(rm.0) << 16) | (u32::from(rn.0) << 5) | u32::from(rd.0));
    }

    // ---- Loads and stores (unsigned scaled offset) ----

    fn ldst(&mut self, base: u32, scale: u32, rt: R, rn: R, offset: u32) {
        assert!(offset % (1 << scale) == 0 && (offset >> scale) < 4096, "ld/st offset out of range");
        self.emit(base | ((offset >> scale) << 10) | (u32::from(rn.0) << 5) | u32::from(rt.0));
    }

    pub(crate) fn ldr(&mut self, size: Size, rt: R, rn: R, offset: u32) {
        match size {
            Size::W => self.ldst(0xB940_0000, 2, rt, rn, offset),
            Size::X => self.ldst(0xF940_0000, 3, rt, rn, offset),
        }
    }

    pub(crate) fn str(&mut self, size: Size, rt: R, rn: R, offset: u32) {
        match size {
            Size::W => self.ldst(0xB900_0000, 2, rt, rn, offset),
            Size::X => self.ldst(0xF900_0000, 3, rt, rn, offset),
        }
    }

    pub(crate) fn ldrb(&mut self, rt: R, rn: R, offset: u32) {
        self.ldst(0x3940_0000, 0, rt, rn, offset);
    }

    pub(crate) fn strb(&mut self, rt: R, rn: R, offset: u32) {
        self.ldst(0x3900_0000, 0, rt, rn, offset);
    }

    pub(crate) fn ldrh(&mut self, rt: R, rn: R, offset: u32) {
        self.ldst(0x7940_0000, 1, rt, rn, offset);
    }

    pub(crate) fn strh(&mut self, rt: R, rn: R, offset: u32) {
        self.ldst(0x7900_0000, 1, rt, rn, offset);
    }

    /// `ldr wt, [xn, xm, lsl #2]`-style register offset (`shift` = log2 of the access size or 0)
    pub(crate) fn ldr_w_reg(&mut self, rt: R, rn: R, rm: R, shifted: bool) {
        self.emit(0xB860_6800 | (u32::from(rm.0) << 16) | (u32::from(shifted) << 12) | (u32::from(rn.0) << 5) | u32::from(rt.0));
    }

    pub(crate) fn str_w_reg(&mut self, rt: R, rn: R, rm: R, shifted: bool) {
        self.emit(0xB820_6800 | (u32::from(rm.0) << 16) | (u32::from(shifted) << 12) | (u32::from(rn.0) << 5) | u32::from(rt.0));
    }

    /// `lsr rd, rn, rm` (LSRV)
    pub(crate) fn lsrv(&mut self, size: Size, rd: R, rn: R, rm: R) {
        self.emit(size.sf() | 0x1AC0_2400 | (u32::from(rm.0) << 16) | (u32::from(rn.0) << 5) | u32::from(rd.0));
    }

    /// `ldrb wt, [xn, xm]`
    pub(crate) fn ldrb_reg(&mut self, rt: R, rn: R, rm: R) {
        self.emit(0x3860_6800 | (u32::from(rm.0) << 16) | (u32::from(rn.0) << 5) | u32::from(rt.0));
    }

    /// `strb wt, [xn, xm]`
    pub(crate) fn strb_reg(&mut self, rt: R, rn: R, rm: R) {
        self.emit(0x3820_6800 | (u32::from(rm.0) << 16) | (u32::from(rn.0) << 5) | u32::from(rt.0));
    }

    pub(crate) fn ldrh_reg(&mut self, rt: R, rn: R, rm: R, shifted: bool) {
        self.emit(0x7860_6800 | (u32::from(rm.0) << 16) | (u32::from(shifted) << 12) | (u32::from(rn.0) << 5) | u32::from(rt.0));
    }

    pub(crate) fn strh_reg(&mut self, rt: R, rn: R, rm: R, shifted: bool) {
        self.emit(0x7820_6800 | (u32::from(rm.0) << 16) | (u32::from(shifted) << 12) | (u32::from(rn.0) << 5) | u32::from(rt.0));
    }

    /// `stp xt1, xt2, [sp, #-imm]!` (pre-index, 64-bit)
    pub(crate) fn stp_pre(&mut self, rt1: R, rt2: R, rn: R, offset: i32) {
        assert!(offset % 8 == 0 && (-512..512).contains(&offset));
        let imm7 = ((offset / 8) as u32) & 0x7F;
        self.emit(0xA980_0000 | (imm7 << 15) | (u32::from(rt2.0) << 10) | (u32::from(rn.0) << 5) | u32::from(rt1.0));
    }

    /// `ldp xt1, xt2, [sp], #imm` (post-index, 64-bit)
    pub(crate) fn ldp_post(&mut self, rt1: R, rt2: R, rn: R, offset: i32) {
        assert!(offset % 8 == 0 && (-512..512).contains(&offset));
        let imm7 = ((offset / 8) as u32) & 0x7F;
        self.emit(0xA8C0_0000 | (imm7 << 15) | (u32::from(rt2.0) << 10) | (u32::from(rn.0) << 5) | u32::from(rt1.0));
    }

    /// `stp xt1, xt2, [xn, #imm]` (signed offset, 64-bit)
    pub(crate) fn stp(&mut self, rt1: R, rt2: R, rn: R, offset: i32) {
        assert!(offset % 8 == 0 && (-512..512).contains(&offset));
        let imm7 = ((offset / 8) as u32) & 0x7F;
        self.emit(0xA900_0000 | (imm7 << 15) | (u32::from(rt2.0) << 10) | (u32::from(rn.0) << 5) | u32::from(rt1.0));
    }

    /// `ldp xt1, xt2, [xn, #imm]` (signed offset, 64-bit)
    pub(crate) fn ldp(&mut self, rt1: R, rt2: R, rn: R, offset: i32) {
        assert!(offset % 8 == 0 && (-512..512).contains(&offset));
        let imm7 = ((offset / 8) as u32) & 0x7F;
        self.emit(0xA940_0000 | (imm7 << 15) | (u32::from(rt2.0) << 10) | (u32::from(rn.0) << 5) | u32::from(rt1.0));
    }

    // ---- Branches ----

    pub(crate) fn b(&mut self, label: Label) {
        self.fixups.push((self.in_cold, self.pos(), label, FixKind::B26));
        self.emit(0x1400_0000);
    }

    /// PC-relative unconditional branch to an already-published target address, resolved at `finish_split`.
    pub(crate) fn b_abs_deferred(&mut self, target: usize) {
        self.abs_fixups.push((self.in_cold, self.pos(), target, false));
        self.emit(0x1400_0000);
    }

    pub(crate) fn bl(&mut self, label: Label) {
        self.fixups.push((self.in_cold, self.pos(), label, FixKind::B26));
        self.emit(0x9400_0000);
    }

    /// PC-relative branch-and-link to an already-published target address, resolved at `finish_split`.
    pub(crate) fn bl_abs_deferred(&mut self, target: usize) {
        self.abs_fixups.push((self.in_cold, self.pos(), target, true));
        self.emit(0x9400_0000);
    }

    pub(crate) fn b_cond(&mut self, cond: Cond, label: Label) {
        self.fixups.push((self.in_cold, self.pos(), label, FixKind::B19));
        self.emit(0x5400_0000 | cond as u32);
    }

    pub(crate) fn cbz(&mut self, size: Size, rt: R, label: Label) {
        self.fixups.push((self.in_cold, self.pos(), label, FixKind::B19));
        self.emit(size.sf() | 0x3400_0000 | u32::from(rt.0));
    }

    pub(crate) fn cbnz(&mut self, size: Size, rt: R, label: Label) {
        self.fixups.push((self.in_cold, self.pos(), label, FixKind::B19));
        self.emit(size.sf() | 0x3500_0000 | u32::from(rt.0));
    }

    pub(crate) fn tbz(&mut self, rt: R, bit: u32, label: Label) {
        assert!(bit < 64);
        self.fixups.push((self.in_cold, self.pos(), label, FixKind::B14));
        self.emit(((bit >> 5) << 31) | 0x3600_0000 | ((bit & 0x1F) << 19) | u32::from(rt.0));
    }

    pub(crate) fn tbnz(&mut self, rt: R, bit: u32, label: Label) {
        assert!(bit < 64);
        self.fixups.push((self.in_cold, self.pos(), label, FixKind::B14));
        self.emit(((bit >> 5) << 31) | 0x3700_0000 | ((bit & 0x1F) << 19) | u32::from(rt.0));
    }

    pub(crate) fn br(&mut self, rn: R) {
        self.emit(0xD61F_0000 | (u32::from(rn.0) << 5));
    }

    pub(crate) fn blr(&mut self, rn: R) {
        self.emit(0xD63F_0000 | (u32::from(rn.0) << 5));
    }

    pub(crate) fn ret(&mut self) {
        self.emit(0xD65F_03C0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use Size::{W, X};

    fn one(f: impl FnOnce(&mut Asm)) -> u32 {
        let mut asm = Asm::new();
        f(&mut asm);
        let code = asm.finish();
        assert_eq!(code.len(), 1);
        code[0]
    }

    // Expected words produced by the LLVM assembler (clang --target=aarch64-linux-android -c,
    // llvm-objdump -d); the instruction text is the assembler input
    #[test]
    fn encodings_match_llvm() {
        let cases: Vec<(&str, u32, Box<dyn Fn(&mut Asm)>)> = vec![
            ("movz w3, #0x1234", 0x52824683, Box::new(|a| a.movz(W, R(3), 0x1234, 0))),
            ("movz x5, #0xbeef, lsl #16", 0xd2b7dde5, Box::new(|a| a.movz(X, R(5), 0xBEEF, 16))),
            ("movk w3, #0x2600, lsl #16", 0x72a4c003, Box::new(|a| a.movk(W, R(3), 0x2600, 16))),
            ("movn w7, #0", 0x12800007, Box::new(|a| a.movn(W, R(7), 0, 0))),
            ("add w1, w2, #0xfff", 0x113ffc41, Box::new(|a| a.add_imm(W, R(1), R(2), 0xFFF))),
            ("add x19, sp, #16", 0x910043f3, Box::new(|a| a.add_imm(X, R(19), SP, 16))),
            ("subs w0, w1, #1", 0x71000420, Box::new(|a| a.subs_imm(W, R(0), R(1), 1))),
            ("cmp w9, #0", 0x7100013f, Box::new(|a| a.cmp_imm(W, R(9), 0))),
            ("add w1, w2, w3", 0x0b030041, Box::new(|a| a.add(W, R(1), R(2), R(3)))),
            ("add x0, x1, x2, lsl #2", 0x8b020820, Box::new(|a| a.add_shifted(X, R(0), R(1), R(2), Shift::Lsl, 2))),
            ("sub w4, w5, w6", 0x4b0600a4, Box::new(|a| a.sub(W, R(4), R(5), R(6)))),
            ("cmp w1, w2", 0x6b02003f, Box::new(|a| a.cmp(W, R(1), R(2)))),
            ("neg w3, w4", 0x4b0403e3, Box::new(|a| a.neg(W, R(3), R(4)))),
            ("adcs w1, w2, w3", 0x3a030041, Box::new(|a| a.adcs(W, R(1), R(2), R(3)))),
            ("sbcs w1, w2, w3", 0x7a030041, Box::new(|a| a.sbcs(W, R(1), R(2), R(3)))),
            ("and w1, w2, w3", 0x0a030041, Box::new(|a| a.and(W, R(1), R(2), R(3)))),
            ("orr w1, w2, w3", 0x2a030041, Box::new(|a| a.orr(W, R(1), R(2), R(3)))),
            ("orr w12, w12, w13, lsr #8", 0x2a4d218c, Box::new(|a| a.orr_shifted(W, R(12), R(12), R(13), Shift::Lsr, 8))),
            ("eor w1, w2, w3", 0x4a030041, Box::new(|a| a.eor(W, R(1), R(2), R(3)))),
            ("bic w1, w2, w3", 0x0a230041, Box::new(|a| a.bic(W, R(1), R(2), R(3)))),
            ("tst w1, w2", 0x6a02003f, Box::new(|a| a.tst(W, R(1), R(2)))),
            ("mvn w1, w2", 0x2a2203e1, Box::new(|a| a.mvn(W, R(1), R(2)))),
            ("mov w20, w21", 0x2a1503f4, Box::new(|a| a.mov(W, R(20), R(21)))),
            ("and w1, w2, #0xff", 0x12001c41, Box::new(|a| assert!(a.and_imm(W, R(1), R(2), 0xFF)))),
            ("and w1, w2, #0xffff0000", 0x12103c41, Box::new(|a| assert!(a.and_imm(W, R(1), R(2), 0xFFFF_0000)))),
            ("orr w1, w2, #0x80000000", 0x32010041, Box::new(|a| assert!(a.orr_imm(W, R(1), R(2), 0x8000_0000)))),
            ("eor w1, w2, #0x55555555", 0x5200f041, Box::new(|a| assert!(a.eor_imm(W, R(1), R(2), 0x5555_5555)))),
            ("tst w0, #0x1", 0x7200001f, Box::new(|a| assert!(a.tst_imm(W, R(0), 1)))),
            ("tst w0, #0x80", 0x7219001f, Box::new(|a| assert!(a.tst_imm(W, R(0), 0x80)))),
            ("and x1, x2, #0xfffffffffffffff0", 0x927cec41, Box::new(|a| assert!(a.and_imm(X, R(1), R(2), 0xFFFF_FFFF_FFFF_FFF0)))),
            ("lsl w1, w2, #2", 0x531e7441, Box::new(|a| a.lsl_imm(W, R(1), R(2), 2))),
            ("lsr w1, w2, #16", 0x53107c41, Box::new(|a| a.lsr_imm(W, R(1), R(2), 16))),
            ("asr w1, w2, #1", 0x13017c41, Box::new(|a| a.asr_imm(W, R(1), R(2), 1))),
            ("lsl x1, x2, #8", 0xd378dc41, Box::new(|a| a.lsl_imm(X, R(1), R(2), 8))),
            ("ror w1, w2, #1", 0x13820441, Box::new(|a| a.ror_imm(W, R(1), R(2), 1))),
            ("extr w9, w9, w10, #16", 0x138a4129, Box::new(|a| a.extr(W, R(9), R(9), R(10), 16))),
            ("bfxil w9, w10, #0, #16", 0x33003d49, Box::new(|a| a.bfm(W, R(9), R(10), 0, 15))),
            ("uxtb w1, w2", 0x53001c41, Box::new(|a| a.uxtb(R(1), R(2)))),
            ("uxth w1, w2", 0x53003c41, Box::new(|a| a.uxth(R(1), R(2)))),
            ("sxtb w1, w2", 0x13001c41, Box::new(|a| a.sxtb(W, R(1), R(2)))),
            ("sxth w1, w2", 0x13003c41, Box::new(|a| a.sxth(W, R(1), R(2)))),
            ("sxtw x1, w2", 0x93407c41, Box::new(|a| a.sxtw(R(1), R(2)))),
            ("rev16 w1, w2", 0x5ac00441, Box::new(|a| a.rev16(W, R(1), R(2)))),
            ("csel w1, w2, w3, eq", 0x1a830041, Box::new(|a| a.csel(W, R(1), R(2), R(3), Cond::Eq))),
            ("csinv w10, wzr, wzr, eq", 0x5a9f03ea, Box::new(|a| a.csinv(W, R(10), ZR, ZR, Cond::Eq))),
            ("csinv w23, w23, wzr, ne", 0x5a9f12f7, Box::new(|a| a.csinv(W, R(23), R(23), ZR, Cond::Ne))),
            ("csinc w10, w10, wzr, ne", 0x1a9f154a, Box::new(|a| a.csinc(W, R(10), R(10), ZR, Cond::Ne))),
            ("cset w9, eq", 0x1a9f17e9, Box::new(|a| a.cset(W, R(9), Cond::Eq))),
            ("cset w9, hs", 0x1a9f37e9, Box::new(|a| a.cset(W, R(9), Cond::Hs))),
            ("mul w1, w2, w3", 0x1b037c41, Box::new(|a| a.mul(W, R(1), R(2), R(3)))),
            ("madd x1, x2, x3, x4", 0x9b031041, Box::new(|a| a.madd(X, R(1), R(2), R(3), R(4)))),
            ("smull x1, w2, w3", 0x9b237c41, Box::new(|a| a.smull(R(1), R(2), R(3)))),
            ("umull x1, w2, w3", 0x9ba37c41, Box::new(|a| a.umull(R(1), R(2), R(3)))),
            ("ldr w1, [x19, #64]", 0xb9404261, Box::new(|a| a.ldr(W, R(1), R(19), 64))),
            ("ldr x1, [x19, #8]", 0xf9400661, Box::new(|a| a.ldr(X, R(1), R(19), 8))),
            ("str w1, [x19, #60]", 0xb9003e61, Box::new(|a| a.str(W, R(1), R(19), 60))),
            ("str x2, [x20]", 0xf9000282, Box::new(|a| a.str(X, R(2), R(20), 0))),
            ("ldrb w1, [x2, #3]", 0x39400c41, Box::new(|a| a.ldrb(R(1), R(2), 3))),
            ("strb w1, [x2, #4095]", 0x393ffc41, Box::new(|a| a.strb(R(1), R(2), 4095))),
            ("ldrh w1, [x2, #2]", 0x79400441, Box::new(|a| a.ldrh(R(1), R(2), 2))),
            ("strh w1, [x2, #6]", 0x79000c41, Box::new(|a| a.strh(R(1), R(2), 6))),
            ("ldr w1, [x2, x3, lsl #2]", 0xb8637841, Box::new(|a| a.ldr_w_reg(R(1), R(2), R(3), true))),
            ("ldrh w1, [x2, x3, lsl #1]", 0x78637841, Box::new(|a| a.ldrh_reg(R(1), R(2), R(3), true))),
            ("ldrh w1, [x2, x3]", 0x78636841, Box::new(|a| a.ldrh_reg(R(1), R(2), R(3), false))),
            ("ldr w1, [x2, x3]", 0xb8636841, Box::new(|a| a.ldr_w_reg(R(1), R(2), R(3), false))),
            ("str w1, [x2, x3]", 0xb8236841, Box::new(|a| a.str_w_reg(R(1), R(2), R(3), false))),
            ("strh w1, [x2, x3]", 0x78236841, Box::new(|a| a.strh_reg(R(1), R(2), R(3), false))),
            ("lsr x14, x14, x11", 0x9acb25ce, Box::new(|a| a.lsrv(X, R(14), R(14), R(11)))),
            ("ldrb w17, [x16, x11]", 0x386b6a11, Box::new(|a| a.ldrb_reg(R(17), R(16), R(11)))),
            ("strb w17, [x16, x11]", 0x382b6a11, Box::new(|a| a.strb_reg(R(17), R(16), R(11)))),
            ("tbz x14, #0, .+4", 0x3600002e, Box::new(|a| { let l = a.new_label(); a.tbz(R(14), 0, l); a.bind(l); })),
            ("stp x29, x30, [sp, #-16]!", 0xa9bf7bfd, Box::new(|a| a.stp_pre(R(29), LR, SP, -16))),
            ("ldp x29, x30, [sp], #16", 0xa8c17bfd, Box::new(|a| a.ldp_post(R(29), LR, SP, 16))),
            ("stp x19, x20, [sp, #16]", 0xa90153f3, Box::new(|a| a.stp(R(19), R(20), SP, 16))),
            ("ldp x19, x20, [sp, #16]", 0xa94153f3, Box::new(|a| a.ldp(R(19), R(20), SP, 16))),
            ("br x16", 0xd61f0200, Box::new(|a| a.br(R(16)))),
            ("blr x8", 0xd63f0100, Box::new(|a| a.blr(R(8)))),
            ("ret", 0xd65f03c0, Box::new(Asm::ret)),
        ];
        for (text, expected, f) in cases {
            let got = one(|a| f(a));
            assert_eq!(got, expected, "{text}: got {got:08x}, expected {expected:08x}");
        }
    }

    #[test]
    fn branches_resolve_labels() {
        let mut a = Asm::new();
        let top = a.new_label();
        let out = a.new_label();
        a.bind(top);
        a.cbz(W, R(1), out); // 0: cbz w1, +4
        a.tbnz(R(2), 3, out); // 1: tbnz w2, #3, +3
        a.b_cond(Cond::Ne, top); // 2: b.ne -2
        a.b(top); // 3: b -3
        a.bind(out);
        a.bl(top); // 4: bl -4
        let code = a.finish();
        // llvm-objdump of the same sequence
        assert_eq!(code, vec![0x34000081, 0x37180062, 0x54ffffc1, 0x17fffffd, 0x97fffffc]);
    }

    #[test]
    fn split_hot_cold_resolves_branches() {
        let stub = 0x1000usize;
        let hot_base = 0x1040usize;
        let cold_base = 0x2000usize;
        let mut a = Asm::new();
        let top = a.new_label();
        let cold_lbl = a.new_label();
        let ret_lbl = a.new_label();
        a.bind(top);
        a.cbz(W, R(1), cold_lbl); // hot[0] at 0x1040 -> cold[0] at 0x2000: delta = +0xFC0 / 4 = +0x3F0
        a.bl_abs_deferred(stub); // hot[1] at 0x1044 -> 0x1000: delta = -0x11 (0x3FFFEF)
        a.bind(ret_lbl);
        a.ret(); // hot[2]
        a.set_cold(true);
        a.bind(cold_lbl);
        a.b(ret_lbl); // cold[0] at 0x2000 -> hot[2] at 0x1048: delta = -0xFEE / 4... wait 0x1048 - 0x2000 = -0xFB8 / 4 = -0x3EE
        a.b_abs_deferred(stub); // cold[1] at 0x2004 -> 0x1000: delta = -0x401
        let (hot, cold) = a.finish_split(hot_base, cold_base);
        assert_eq!(hot, vec![0x3400_7E01, 0x97FF_FFEF, 0xD65F_03C0]);
        assert_eq!(cold, vec![0x17FF_FC12, 0x17FF_FBFF]);
    }

    #[test]
    fn mov_imm32_uses_short_forms() {
        let assemble = |v: u32| {
            let mut a = Asm::new();
            a.mov_imm32(R(0), v);
            a.finish()
        };
        assert_eq!(assemble(0x1234), vec![0x52824680]); // movz w0, #0x1234
        assert_eq!(assemble(0x0600_0000), vec![0x52a0c000]); // movz w0, #0x600, lsl #16
        assert_eq!(assemble(0xFFFF_FFFE), vec![0x12800020]); // movn w0, #1
        assert_eq!(assemble(0x00FF_00FF), vec![0x32009fe0]); // orr w0, wzr, #0xff00ff
        assert_eq!(assemble(0x2600_1234), vec![0x52824680, 0x72a4c000]); // movz + movk
    }

    #[test]
    fn logical_immediates_roundtrip() {
        // Decode (N, immr, imms) back to the value and compare, for every encodable 32-bit pattern
        fn decode(n: u32, immr: u32, imms: u32, width: u32) -> u64 {
            let len = 31 - ((n << 6) | (!imms & 0x3F)).leading_zeros();
            let esize = 1u32 << len;
            let s = imms & (esize - 1);
            let r = immr & (esize - 1);
            let emask = if esize == 64 { u64::MAX } else { (1u64 << esize) - 1 };
            let elem = (1u64 << (s + 1)) - 1;
            let rotated = if r == 0 { elem } else { ((elem >> r) | (elem << (esize - r))) & emask };
            let mut v = 0u64;
            let mut i = 0;
            while i < width {
                v |= rotated << i;
                i += esize;
            }
            v
        }
        let mut count = 0;
        for esize in [2u32, 4, 8, 16, 32] {
            for ones in 1..esize {
                for rot in 0..esize {
                    let emask = (1u64 << esize) - 1;
                    let elem = (1u64 << ones) - 1;
                    let rotated = if rot == 0 { elem } else { ((elem >> rot) | (elem << (esize - rot))) & emask };
                    let mut v = 0u64;
                    let mut i = 0;
                    while i < 32 {
                        v |= rotated << i;
                        i += esize;
                    }
                    let (n, immr, imms) = logical_imm(v, Size::W).expect("encodable");
                    assert_eq!(decode(n, immr, imms, 32), v, "{v:08x}");
                    count += 1;
                }
            }
        }
        assert!(count > 1000);
        assert_eq!(logical_imm(0, Size::W), None);
        assert_eq!(logical_imm(0xFFFF_FFFF, Size::W), None);
        assert_eq!(logical_imm(0x1234_5678, Size::W), None);
    }
}
