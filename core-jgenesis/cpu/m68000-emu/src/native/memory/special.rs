//! Privileged register transitions and explicit exception exits. Generated code
//! selects/tests the architectural operation; callbacks commit only primitive
//! state or return a typed exception to the shared exception-entry pipeline.
use super::*;
use crate::core::instructions::{BranchCondition, UspDirection};
pub(crate) fn base_cycles(opcode: u16) -> Option<u32> {
    Some(match Instruction::decode_from(opcode) {
        Instruction::MoveFromSr(dest) => {
            if matches!(dest, AddressingMode::DataDirect(..)) {
                6
            } else {
                8 + dest.address_calculation_cycles(OpSize::Word)
            }
        }
        Instruction::MoveToCcr(source) | Instruction::MoveToSr(source) => {
            12 + source.address_calculation_cycles(OpSize::Word)
        }
        Instruction::AndToCcr
        | Instruction::OrToCcr
        | Instruction::ExclusiveOrToCcr
        | Instruction::AndToSr
        | Instruction::OrToSr
        | Instruction::ExclusiveOrToSr => 20,
        Instruction::MoveUsp(..) | Instruction::Stop | Instruction::TrapOnOverflow => 4,
        Instruction::Reset => 132,
        Instruction::ReturnFromException => 20,
        Instruction::Trap(..) => 0,
        Instruction::Illegal { .. }
            if opcode == 0x4AFC || opcode >> 12 == 0xA || opcode >> 12 == 0xF =>
        {
            0
        }
        _ => return None,
    })
}
impl Emitter {
    pub(super) fn raise(&mut self, kind: u32, value: u32) {
        self.imm(1, kind);
        self.imm(2, value);
        self.call(88);
    }
    fn privilege(&mut self) {
        self.code.push(0xB9406269);
        let allowed = self.code.len();
        self.code.push(0x35000009);
        self.raise(0, 0);
        self.patch(allowed);
    }
    fn status(&mut self, op: u32, value: u32) {
        self.imm(1, op);
        self.mov(2, value);
        self.call(80);
    }
}
pub(super) fn emit(opcode: u16) -> Option<Vec<u32>> {
    base_cycles(opcode)?;
    let instruction = Instruction::decode_from(opcode);
    let mut e = Emitter::new();
    match instruction {
        Instruction::MoveFromSr(dest) => {
            let (m, r) = ea(dest);
            if m > 1 {
                e.address(m, r, 2);
                if m == 3 {
                    e.mov(23, 21);
                    e.post(m, r, 2);
                    e.mov(21, 23);
                }
            }
            e.status(0, 20);
            e.mov(20, 0);
            e.write_resolved(m, r, 2);
        }
        Instruction::MoveToCcr(source) | Instruction::MoveToSr(source) => {
            let sr = matches!(instruction, Instruction::MoveToSr(..));
            if sr {
                e.privilege();
            }
            let (m, r) = ea(source);
            e.read_operand(m, r, 2);
            if sr {
                e.status(1, 20);
            } else {
                e.mov(1, 20);
                e.call(40);
            }
        }
        Instruction::AndToCcr
        | Instruction::OrToCcr
        | Instruction::ExclusiveOrToCcr
        | Instruction::AndToSr
        | Instruction::OrToSr
        | Instruction::ExclusiveOrToSr => {
            let sr = matches!(
                instruction,
                Instruction::AndToSr | Instruction::OrToSr | Instruction::ExclusiveOrToSr
            );
            if sr {
                e.privilege();
            }
            e.fetch();
            e.mov(20, 0);
            if sr {
                e.status(0, 20);
                e.mov(1, 0);
            } else {
                e.code.push(0xB9403661);
            }
            let op = match instruction {
                Instruction::AndToCcr | Instruction::AndToSr => 0x0A140021,
                Instruction::OrToCcr | Instruction::OrToSr => 0x2A140021,
                _ => 0x4A140021,
            };
            e.code.push(op);
            if sr {
                e.mov(20, 1);
                e.status(1, 20);
            } else {
                e.call(40);
            }
        }
        Instruction::MoveUsp(direction, register) => {
            e.privilege();
            if direction == UspDirection::RegisterToUsp {
                e.reg(u32::from(register.0) + 8, 20);
                e.status(3, 20);
            } else {
                e.status(2, 20);
                e.mov(20, 0);
                e.write_reg(u32::from(register.0) + 8, 20, 4);
            }
        }
        Instruction::Stop => {
            e.privilege();
            e.fetch();
            e.mov(20, 0);
            e.status(1, 20);
            e.status(4, 20);
        }
        Instruction::ReturnFromException => {
            e.privilege();
            e.pop(2);
            e.mov(24, 20);
            e.pop(4);
            e.mov(25, 20);
            e.status(1, 24);
            e.mov(1, 25);
            e.call(64);
        }
        Instruction::Reset => {}
        Instruction::Trap(vector) => e.raise(2, 32 + vector),
        Instruction::TrapOnOverflow => {
            e.condition(BranchCondition::OverflowSet);
            let clear = e.cbz(20);
            e.raise(2, 7);
            e.patch(clear);
        }
        Instruction::Illegal { .. } => e.raise(1, u32::from(opcode)),
        _ => unreachable!(),
    }
    Some(e.finish())
}
