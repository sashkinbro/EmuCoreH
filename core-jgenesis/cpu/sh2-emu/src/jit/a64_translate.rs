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

use super::a64::{Asm, Cond, R, Size::W};
use crate::registers::{Sh2Registers, StatusRegister};
use std::mem::offset_of;

/// Guest register file pointer in generated code
pub(crate) const REGS: R = R(19);
const T0: R = R(9);
const T1: R = R(10);
const T2: R = R(11);

fn gpr(n: usize) -> u32 {
    (offset_of!(Sh2Registers, gpr) + 4 * n) as u32
}

fn t_offset() -> u32 {
    (offset_of!(Sh2Registers, sr) + offset_of!(StatusRegister, t)) as u32
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
        0x3008 => binary(a, opcode, |a, d, x, y| a.sub(W, d, x, y)),
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
        0x600C => unary(a, opcode, |a, d, x| a.uxtb(d, x)),
        0x600D => unary(a, opcode, |a, d, x| a.uxth(d, x)),
        0x600E => unary(a, opcode, |a, d, x| a.sxtb(W, d, x)),
        0x600F => unary(a, opcode, |a, d, x| a.sxth(W, d, x)),
        0x6007 => unary(a, opcode, |a, d, x| a.mvn(W, d, x)),
        0x600B => unary(a, opcode, |a, d, x| a.neg(W, d, x)),
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
        (0x6003, RR), (0x300C, RR), (0x3008, RR), (0x2009, RR), (0x200B, RR), (0x200A, RR),
        (0x2008, RR), (0x3000, RR), (0x3002, RR), (0x3003, RR), (0x3006, RR), (0x3007, RR),
        (0x600C, RR), (0x600D, RR), (0x600E, RR), (0x600F, RR), (0x6007, RR), (0x600B, RR),
        (0x6008, RR), (0x6009, RR), (0x200D, RR), (0x4011, RN), (0x4015, RN), (0x4010, RN),
        (0x4000, RN), (0x4020, RN), (0x4001, RN), (0x4021, RN), (0x4004, RN), (0x4005, RN),
        (0x4008, RN), (0x4018, RN), (0x4028, RN), (0x4009, RN), (0x4019, RN), (0x4029, RN),
        (0x0029, RN), (0x0009, 0), (0x0008, 0), (0x0018, 0), (0xE000, RN_IMM8),
        (0x7000, RN_IMM8), (0x8800, IMM8), (0xC800, IMM8), (0xC900, IMM8), (0xCA00, IMM8),
        (0xCB00, IMM8),
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
    fn unknown_instructions_are_not_translated() {
        let mut asm = Asm::new();
        for opcode in [0x6012u16, 0xA000, 0x8900, 0x000B, 0xD123, 0xC500, 0x400B] {
            assert!(!translate(&mut asm, opcode), "{opcode:04X}");
        }
        assert!(asm.finish().is_empty());
    }
}
