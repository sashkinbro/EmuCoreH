//! Native bit operations, multiply and TAS. Data-dependent flags and timing are
//! computed by generated instructions, never an instruction execution callback.
use super::*;
#[derive(Clone, Copy)]
enum Bit {
    Test,
    Change,
    Clear,
    Set,
}
fn bit_plan(opcode: u16) -> Option<(Bit, AddressingMode, AddressingMode)> {
    Some(match Instruction::decode_from(opcode) {
        Instruction::BitTest { source, dest } => (Bit::Test, source, dest),
        Instruction::BitTestAndChange { source, dest } => (Bit::Change, source, dest),
        Instruction::BitTestAndClear { source, dest } => (Bit::Clear, source, dest),
        Instruction::BitTestAndSet { source, dest } => (Bit::Set, source, dest),
        _ => return None,
    })
}
pub(crate) fn base_cycles(opcode: u16) -> Option<u32> {
    if let Some((op, source, dest)) = bit_plan(opcode) {
        let immediate = u32::from(source == AddressingMode::Immediate) * 4;
        return Some(match op {
            Bit::Test => {
                4 + immediate
                    + match dest {
                        AddressingMode::DataDirect(..) => 2,
                        AddressingMode::Immediate => 6,
                        _ => dest.address_calculation_cycles(OpSize::Byte),
                    }
            }
            _ => {
                if matches!(dest, AddressingMode::DataDirect(..)) {
                    0
                } else {
                    8 + immediate + dest.address_calculation_cycles(OpSize::Byte)
                }
            }
        });
    }
    Some(match Instruction::decode_from(opcode) {
        Instruction::MultiplySigned(..) | Instruction::MultiplyUnsigned(..) => 0,
        Instruction::TestAndSet(dest) => {
            if matches!(dest, AddressingMode::DataDirect(..)) {
                4
            } else {
                10 + dest.address_calculation_cycles(OpSize::Byte)
            }
        }
        _ => return None,
    })
}
pub(super) fn emit(opcode: u16) -> Option<Vec<u32>> {
    base_cycles(opcode)?;
    let mut e = Emitter::new();
    if let Some((op, source, dest)) = bit_plan(opcode) {
        let (sm, sr) = ea(source);
        e.read_operand(sm, sr, 1);
        e.code.push(if matches!(dest, AddressingMode::DataDirect(..)) {
            0x53001298
        } else {
            0x53000A98
        });
        let (dm, dr) = ea(dest);
        let size = if dm == 0 { 4 } else { 1 };
        e.read_operand(dm, dr, size);
        e.imm(22, 1);
        e.code.push(0x1AD822D6);
        e.code.extend([0x6A16029F, 0x1A9F17E1, 0xB9403662]);
        e.imm(3, 4);
        e.code.extend([0x0A230042, 0x2A010841]);
        e.call(40);
        if !matches!(op, Bit::Test) {
            if dm == 0 {
                e.code.push(0x53047F09);
                e.code.push(0x531F7929);
                let base = 6
                    + u32::from(source == AddressingMode::Immediate) * 4
                    + if matches!(op, Bit::Clear) { 2 } else { 0 };
                e.code.push(0x11000129 | (base << 10));
                e.code.push(0xB9004A69);
            }
            e.code.push(match op {
                Bit::Change => 0x4A160294,
                Bit::Clear => 0x0A360294,
                Bit::Set => 0x2A160294,
                _ => unreachable!(),
            });
            e.write_resolved(dm, dr, size);
        }
        return Some(e.finish());
    }
    match Instruction::decode_from(opcode) {
        Instruction::MultiplySigned(register, source)
        | Instruction::MultiplyUnsigned(register, source) => {
            let signed =
                matches!(Instruction::decode_from(opcode), Instruction::MultiplySigned(..));
            let (sm, sr) = ea(source);
            e.read_operand(sm, sr, 2);
            e.mov(24, 20);
            e.reg(register.0.into(), 20);
            if signed {
                e.code.extend([0x13003F18, 0x13003E94]);
            } else {
                e.code.extend([0x53003F18, 0x53003E94]);
            }
            e.code.push(0x1B187E94);
            e.logical_flags(4);
            e.mov(22, 24);
            if signed {
                e.code.push(0x4A1806D6);
            }
            e.code.push(0x53003ED6);
            e.imm(9, 0);
            let done = e.cbz(22);
            let start = e.code.len();
            e.code.extend([0x510006C1, 0x0A0102D6, 0x11000529]);
            let delta = start as i32 - e.code.len() as i32;
            e.code.push(0x35000016 | (((delta as u32) & 0x7FFFF) << 5));
            e.patch(done);
            e.code.push(0x531F7929);
            let base = 38 + source.address_calculation_cycles(OpSize::Word);
            e.code.push(0x11000129 | (base << 10));
            e.code.push(0xB9004A69);
            e.write_reg(register.0.into(), 20, 4);
        }
        Instruction::TestAndSet(dest) => {
            let (dm, dr) = ea(dest);
            e.read_operand(dm, dr, 1);
            e.logical_flags(1);
            let skip = if dm == 0 {
                None
            } else {
                e.code.push(0xB9404E69);
                Some(e.cbz(9))
            };
            e.imm(22, 128);
            e.code.push(0x2A160294);
            e.write_resolved(dm, dr, 1);
            if let Some(skip) = skip {
                e.patch(skip);
            }
        }
        _ => unreachable!(),
    }
    Some(e.finish())
}
