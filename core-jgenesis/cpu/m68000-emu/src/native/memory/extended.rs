//! ADDX/SUBX/NEGX and decimal arithmetic. No instruction-semantic Rust helpers.
use super::*;
#[derive(Clone, Copy)]
enum Kind {
    Add,
    Sub,
    Neg,
    Abcd,
    Sbcd,
    Nbcd,
}
fn plan(opcode: u16) -> Option<(Kind, OpSize, AddressingMode, AddressingMode)> {
    Some(match Instruction::decode_from(opcode) {
        Instruction::Add { size, source, dest, with_extend: true } => {
            (Kind::Add, size, source, dest)
        }
        Instruction::Subtract { size, source, dest, with_extend: true } => {
            (Kind::Sub, size, source, dest)
        }
        Instruction::Negate { size, dest, with_extend: true } => {
            (Kind::Neg, size, AddressingMode::Quick(0), dest)
        }
        Instruction::AddDecimal { source, dest } => (Kind::Abcd, OpSize::Byte, source, dest),
        Instruction::SubtractDecimal { source, dest } => (Kind::Sbcd, OpSize::Byte, source, dest),
        Instruction::NegateDecimal(dest) => {
            (Kind::Nbcd, OpSize::Byte, AddressingMode::Quick(0), dest)
        }
        _ => return None,
    })
}
pub(crate) fn base_cycles(opcode: u16) -> Option<u32> {
    let (kind, size, source, dest) = plan(opcode)?;
    let direct = matches!(source, AddressingMode::DataDirect(..));
    Some(match kind {
        Kind::Add | Kind::Sub => {
            if direct {
                if size == OpSize::LongWord { 8 } else { 4 }
            } else {
                if size == OpSize::LongWord { 30 } else { 18 }
            }
        }
        Kind::Neg => unary_cycles(size, dest),
        Kind::Abcd | Kind::Sbcd => {
            if direct {
                6
            } else {
                18
            }
        }
        Kind::Nbcd => {
            if matches!(dest, AddressingMode::DataDirect(..)) {
                6
            } else {
                unary_cycles(size, dest)
            }
        }
    })
}
fn unary_cycles(size: OpSize, dest: AddressingMode) -> u32 {
    let direct = matches!(dest, AddressingMode::DataDirect(..));
    (if size == OpSize::LongWord { if direct { 6 } else { 8 } } else { 4 })
        + dest.address_calculation_cycles(size)
        + if direct { 0 } else { 4 }
}
impl Emitter {
    fn read_extended(&mut self, m: u16, r: u16, size: u32) {
        if m == 4 && size == 4 {
            self.reg(u32::from(r) + 8, 21);
            self.code.push(0x51000AB5);
            self.write_reg(u32::from(r) + 8, 21, 4);
            self.mov(1, 21);
            self.imm(2, 2);
            self.call(8);
            self.mov(20, 0);
            self.code.push(0x51000AB5);
            self.write_reg(u32::from(r) + 8, 21, 4);
            self.mov(1, 21);
            self.imm(2, 2);
            self.call(8);
            self.code.push(0x2A004294);
        } else {
            self.read_operand(m, r, size);
        }
    }
    pub(super) fn op(&mut self, base: u32, rd: u32, rn: u32, rm: u32) {
        self.code.push(base | (rm << 16) | (rn << 5) | rd);
    }
    pub(super) fn ubfx(&mut self, rd: u32, rn: u32, bit: u32, width: u32) {
        self.code.push(0x53000000 | (bit << 16) | ((bit + width - 1) << 10) | (rn << 5) | rd);
    }
    pub(super) fn cset(&mut self, rd: u32, cond: u32) {
        self.code.push(0x1A9F07E0 | ((cond ^ 1) << 12) | rd);
    }
    pub(super) fn cmp_imm(&mut self, r: u32, v: u32) {
        self.code.push(0x7100001F | (v << 10) | (r << 5));
    }
    pub(super) fn mask_width(&mut self, r: u32, bits: u32) {
        if bits < 32 {
            self.ubfx(r, r, 0, bits);
        }
    }
    // Input w3=C, w4=V, w20=result. Sticky guest Z and X=C.
    fn extended_flags(&mut self, bits: u32) {
        self.code.extend([0x2A040463, 0x12000064, 0x2A041063, 0xB9403665]);
        self.ubfx(5, 5, 2, 1);
        self.cmp_imm(20, 0);
        self.cset(4, 0);
        self.op(0x0A000000, 4, 4, 5);
        self.code.push(0x2A040863);
        self.ubfx(4, 20, bits - 1, 1);
        self.code.push(0x2A040C63);
        self.mov(1, 3);
        self.call(40);
    }
    fn extend_alu(&mut self, add: bool, bits: u32) {
        self.mask_width(20, bits);
        self.mask_width(24, bits);
        self.code.push(0xB9403662);
        self.ubfx(2, 2, 4, 1);
        self.op(if add { 0x8B000000 } else { 0xCB000000 }, 1, 20, 24);
        self.op(if add { 0x8B000000 } else { 0xCB000000 }, 1, 1, 2);
        let bit = if add { bits } else { 63 };
        self.code.push(0xD3400000 | (bit << 16) | (bit << 10) | (1 << 5) | 3);
        self.op(0x4A000000, 4, 20, 24);
        if add {
            self.code.push(0x2A2403E4);
        }
        self.op(0x4A000000, 5, 20, 1);
        self.op(0x0A000000, 4, 4, 5);
        self.ubfx(4, 4, bits - 1, 1);
        self.mov(20, 1);
        self.mask_width(20, bits);
        self.extended_flags(bits);
    }
    fn decimal(&mut self, add: bool) {
        self.mask_width(20, 8);
        self.mask_width(24, 8);
        self.code.push(0xB9403662);
        self.ubfx(2, 2, 4, 1);
        self.op(if add { 0x0B000000 } else { 0x4B000000 }, 1, 20, 24);
        self.op(if add { 0x0B000000 } else { 0x4B000000 }, 1, 1, 2);
        self.ubfx(3, 1, if add { 8 } else { 31 }, 1);
        self.mask_width(1, 8);
        self.ubfx(4, 20, 0, 4);
        self.ubfx(5, 24, 0, 4);
        self.op(0x0B000000, 5, 5, 2);
        if add {
            self.op(0x0B000000, 4, 4, 5);
            self.cmp_imm(4, 16);
            self.cset(4, 2);
            self.ubfx(5, 1, 0, 4);
            self.cmp_imm(5, 9);
            self.cset(5, 8);
            self.op(0x2A000000, 4, 4, 5);
        } else {
            self.op(0x6B000000, 31, 4, 5);
            self.cset(4, 3);
        }
        self.imm(6, 6);
        self.op(0x1B007C00, 6, 4, 6);
        if add {
            self.cmp_imm(1, 0x99);
            self.cset(5, 8);
            self.op(0x2A000000, 5, 5, 3);
        } else {
            self.mov(5, 3);
        }
        self.imm(7, 0x60);
        self.op(0x1B007C00, 7, 5, 7);
        self.op(0x0B000000, 6, 6, 7);
        self.op(if add { 0x0B000000 } else { 0x4B000000 }, 7, 1, 6);
        self.ubfx(4, 7, if add { 8 } else { 31 }, 1);
        self.ubfx(1, 1, 0, 7);
        self.ubfx(6, 6, 0, 7);
        if add {
            self.op(0x0B000000, 1, 1, 6);
            self.cmp_imm(1, 128);
            self.cset(5, 2);
        } else {
            self.op(0x6B000000, 31, 1, 6);
            self.cset(5, 3);
        }
        self.op(0x4A000000, 5, 5, 4);
        self.op(0x2A000000, 3, 3, 4);
        self.mov(4, 5);
        self.mov(20, 7);
        self.mask_width(20, 8);
        self.extended_flags(8);
    }
}
pub(super) fn emit(opcode: u16) -> Option<Vec<u32>> {
    let (kind, size, source, dest) = plan(opcode)?;
    let size = match size {
        OpSize::Byte => 1,
        OpSize::Word => 2,
        OpSize::LongWord => 4,
    };
    let mut e = Emitter::new();
    let (sm, sr) = ea(source);
    let (dm, dr) = ea(dest);
    if matches!(kind, Kind::Neg | Kind::Nbcd) {
        e.read_operand(dm, dr, size);
        e.mov(24, 20);
        e.imm(20, 0);
    } else {
        e.read_extended(sm, sr, size);
        e.mov(24, 20);
        e.read_extended(dm, dr, size);
    }
    match kind {
        Kind::Add => e.extend_alu(true, size * 8),
        Kind::Sub | Kind::Neg => e.extend_alu(false, size * 8),
        Kind::Abcd => e.decimal(true),
        Kind::Sbcd | Kind::Nbcd => e.decimal(false),
    }
    e.write_resolved(dm, dr, size);
    Some(e.finish())
}
