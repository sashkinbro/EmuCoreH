//! Exact-width shift/rotate loop emitted entirely as A64, including zero-count
//! C/X differences, sticky ASL overflow, register count modulo 64 and cycle count.
use super::*;
use crate::core::instructions::{ShiftCount, ShiftDirection};
#[derive(Clone, Copy)]
enum Kind {
    Arithmetic,
    Logical,
    Rotate,
    Extend,
}
fn plan(opcode: u16) -> Option<(Kind, OpSize, ShiftDirection, AddressingMode, Option<ShiftCount>)> {
    use Instruction::*;
    Some(match Instruction::decode_from(opcode) {
        ArithmeticShiftRegister(s, d, r, c) => {
            (Kind::Arithmetic, s, d, AddressingMode::DataDirect(r), Some(c))
        }
        LogicalShiftRegister(s, d, r, c) => {
            (Kind::Logical, s, d, AddressingMode::DataDirect(r), Some(c))
        }
        RotateRegister(s, d, r, c) => (Kind::Rotate, s, d, AddressingMode::DataDirect(r), Some(c)),
        RotateThruExtendRegister(s, d, r, c) => {
            (Kind::Extend, s, d, AddressingMode::DataDirect(r), Some(c))
        }
        ArithmeticShiftMemory(d, a) => (Kind::Arithmetic, OpSize::Word, d, a, None),
        LogicalShiftMemory(d, a) => (Kind::Logical, OpSize::Word, d, a, None),
        RotateMemory(d, a) => (Kind::Rotate, OpSize::Word, d, a, None),
        RotateThruExtendMemory(d, a) => (Kind::Extend, OpSize::Word, d, a, None),
        _ => return None,
    })
}
pub(crate) fn base_cycles(opcode: u16) -> Option<u32> {
    let (_, _, _, dest, count) = plan(opcode)?;
    Some(if count.is_none() { 8 + dest.address_calculation_cycles(OpSize::Word) } else { 0 })
}
fn bit(code: &mut Vec<u32>, out: u32, input: u32, bit: u32) {
    code.push(0x53000000 | (bit << 16) | (bit << 10) | (input << 5) | out);
}
fn mask(code: &mut Vec<u32>, r: u32, bits: u32) {
    if bits < 32 {
        code.push(0x53000000 | ((bits - 1) << 10) | (r << 5) | r);
    }
}
pub(super) fn emit(opcode: u16) -> Option<Vec<u32>> {
    let (kind, size, direction, dest, count) = plan(opcode)?;
    let size = match size {
        OpSize::Byte => 1,
        OpSize::Word => 2,
        OpSize::LongWord => 4,
    };
    let bits = size * 8;
    let mut e = Emitter::new();
    if let Some(count) = count {
        match count {
            ShiftCount::Constant(n) => e.imm(22, n.into()),
            ShiftCount::Register(r) => {
                e.reg(r.0.into(), 22);
                e.code.push(0x530016D6);
            }
        }
        e.code.push(0x531F7AC9);
        e.code.push(0x11000129 | ((if size == 4 { 8 } else { 6 }) << 10));
        e.code.push(0xB9004A69);
    } else {
        e.imm(22, 1);
    }
    let (m, r) = ea(dest);
    e.read_operand(m, r, size);
    // EA extension/index lowering uses w22; memory shifts always execute once.
    if count.is_none() {
        e.imm(22, 1);
    }
    mask(&mut e.code, 20, bits);
    e.code.push(0xB9403665);
    bit(&mut e.code, 24, 5, 4);
    e.imm(25, 0);
    if matches!(kind, Kind::Extend) {
        e.mov(23, 24);
    } else {
        e.imm(23, 0);
    }
    let skip = e.cbz(22);
    let start = e.code.len();
    bit(&mut e.code, 1, 20, bits - 1);
    bit(&mut e.code, 23, 20, if direction == ShiftDirection::Left { bits - 1 } else { 0 });
    if direction == ShiftDirection::Left {
        e.code.push(0x531F7A94);
    } else {
        if matches!(kind, Kind::Arithmetic) && bits < 32 {
            e.code.push(if bits == 8 { 0x13001E94 } else { 0x13003E94 });
        }
        e.code.push(if matches!(kind, Kind::Arithmetic) { 0x13017E94 } else { 0x53017E94 });
    }
    if matches!(kind, Kind::Rotate | Kind::Extend) {
        let incoming = if matches!(kind, Kind::Extend) { 24 } else { 23 };
        if direction == ShiftDirection::Left {
            e.code.push(0x2A000294 | (incoming << 16));
        } else {
            e.code.push(0x2A000294 | (incoming << 16) | ((bits - 1) << 10));
        }
    }
    mask(&mut e.code, 20, bits);
    if matches!(kind, Kind::Arithmetic) {
        bit(&mut e.code, 2, 20, bits - 1);
        e.code.extend([0x4A020022, 0x2A020339]);
    }
    if !matches!(kind, Kind::Rotate) {
        e.mov(24, 23);
    }
    e.code.push(0x510006D6);
    let delta = start as i32 - e.code.len() as i32;
    e.code.push(0x35000016 | (((delta as u32) & 0x7FFFF) << 5));
    e.patch(skip);
    e.code.extend([0x531C6F01, 0x2A170021, 0x2A190421, 0x7100029F, 0x1A9F17E2, 0x2A020821]);
    bit(&mut e.code, 2, 20, bits - 1);
    e.code.push(0x2A020C21);
    e.call(40);
    e.write_resolved(m, r, size);
    Some(e.finish())
}
