//! Control/stack/address operations lowered to A64, with primitive prefetch/bus callbacks.
use super::*;
use crate::core::instructions::{BranchCondition, Instruction};
pub(crate) fn base_cycles(opcode: u16) -> Option<u32> {
    Some(match Instruction::decode_from(opcode) {
        Instruction::Branch(..) | Instruction::BranchDecrement(..) | Instruction::Set(..) => 0,
        Instruction::BranchToSubroutine(..) => 18,
        Instruction::LoadEffectiveAddress(a, _) => effective_cycles(a),
        Instruction::PushEffectiveAddress(a) => 8 + effective_cycles(a),
        Instruction::Jump(a) => jump_cycles(a),
        Instruction::JumpToSubroutine(a) => 8 + jump_cycles(a),
        Instruction::ExchangeData(..)
        | Instruction::ExchangeAddress(..)
        | Instruction::ExchangeDataAddress(..) => 6,
        Instruction::Link(..) => 16,
        Instruction::Unlink(..) => 12,
        Instruction::Return { restore_ccr } => {
            if restore_ccr {
                20
            } else {
                16
            }
        }
        _ => return None,
    })
}
fn effective_cycles(a: AddressingMode) -> u32 {
    match a {
        AddressingMode::AddressIndirectIndexed(..) | AddressingMode::PcRelativeIndexed => 12,
        _ => a.address_calculation_cycles(OpSize::Byte),
    }
}
fn jump_cycles(a: AddressingMode) -> u32 {
    use AddressingMode::*;
    match a {
        AddressIndirect(..) => 8,
        AddressIndirectDisplacement(..) | PcRelativeDisplacement | AbsoluteShort => 10,
        AddressIndirectIndexed(..) | PcRelativeIndexed => 14,
        AbsoluteLong => 12,
        _ => unreachable!(),
    }
}
impl Emitter {
    pub(super) fn condition(&mut self, condition: BranchCondition) {
        self.code.push(0xB9403665);
        for (r, bit) in [(1, 0), (2, 2), (3, 3), (4, 1)] {
            self.code.push(0x53000000 | (bit << 16) | (bit << 10) | (5 << 5) | r);
        }
        use BranchCondition::*;
        match condition {
            True => self.imm(20, 1),
            False => self.imm(20, 0),
            Higher => self.code.extend([0x2A020034, 0x52000294]),
            LowerOrSame => self.code.push(0x2A020034),
            CarryClear => self.code.push(0x52000034),
            CarrySet => self.mov(20, 1),
            NotEqual => self.code.push(0x52000054),
            Equal => self.mov(20, 2),
            OverflowClear => self.code.push(0x52000094),
            OverflowSet => self.mov(20, 4),
            Plus => self.code.push(0x52000074),
            Minus => self.mov(20, 3),
            GreaterOrEqual => self.code.extend([0x4A040074, 0x52000294]),
            LessThan => self.code.push(0x4A040074),
            GreaterThan => self.code.extend([0x4A040074, 0x2A020294, 0x52000294]),
            LessOrEqual => self.code.extend([0x4A040074, 0x2A020294]),
        }
    }
    pub(super) fn cycles(&mut self, value: u32) {
        self.imm(9, value);
        self.code.push(0xB9004A69);
    }
    fn label(&self) -> usize {
        self.code.len()
    }
    pub(super) fn cbz(&mut self, r: u32) -> usize {
        let i = self.label();
        self.code.push(0x34000000 | r);
        i
    }
    pub(super) fn b(&mut self) -> usize {
        let i = self.label();
        self.code.push(0x14000000);
        i
    }
    pub(super) fn patch(&mut self, i: usize) {
        let d = (self.code.len() - i) as u32;
        if self.code[i] & 0x7C000000 == 0x14000000 {
            self.code[i] |= d;
        } else {
            self.code[i] |= d << 5;
        }
    }
    fn push(&mut self) {
        self.reg(15, 23);
        self.code.push(0x510012F7);
        self.write_reg(15, 23, 4);
        self.mov(1, 23);
        self.mov(2, 20);
        self.imm(3, 4);
        self.call(16);
    }
    pub(super) fn pop(&mut self, size: u32) {
        self.reg(15, 21);
        self.mov(1, 21);
        self.imm(2, size);
        self.call(8);
        self.mov(20, 0);
        self.code.push(0x110002B5 | (size << 10));
        self.write_reg(15, 21, 4);
    }
    fn displacement(&mut self, disp: i8) {
        self.reg(16, 21);
        if disp == 0 {
            self.fetch();
            self.code.push(0x13003C16);
        } else {
            self.imm(22, disp as i32 as u32);
        }
    }
    fn relative_jump(&mut self) {
        self.code.push(0x0B1602A1);
        self.call(64);
    }
}
pub(super) fn emit(opcode: u16) -> Option<Vec<u32>> {
    let instruction = Instruction::decode_from(opcode);
    base_cycles(opcode)?;
    let mut e = Emitter::new();
    match instruction {
        Instruction::Branch(condition, disp) => {
            e.displacement(disp);
            e.condition(condition);
            let no = e.cbz(20);
            e.relative_jump();
            e.cycles(10);
            let end = e.b();
            e.patch(no);
            e.cycles(if disp == 0 { 12 } else { 8 });
            e.patch(end);
        }
        Instruction::BranchToSubroutine(disp) => {
            e.displacement(disp);
            e.reg(16, 20);
            e.push();
            e.relative_jump();
        }
        Instruction::BranchDecrement(condition, register) => {
            e.displacement(0);
            e.condition(condition);
            let perform = e.cbz(20);
            e.cycles(12);
            let finish_true = e.b();
            e.patch(perform);
            e.reg(register.0.into(), 20);
            e.code.push(0x53003E98);
            e.code.push(0x51000694);
            e.write_reg(register.0.into(), 20, 2);
            let expired = e.cbz(24);
            e.relative_jump();
            e.cycles(10);
            let finish_branch = e.b();
            e.patch(expired);
            e.cycles(14);
            e.patch(finish_true);
            e.patch(finish_branch);
        }
        Instruction::Set(condition, dest) => {
            e.condition(condition);
            if matches!(dest, AddressingMode::DataDirect(..)) {
                e.code.push(0x531F7A89);
                e.code.push(0x11001129);
                e.code.push(0xB9004A69);
            } else {
                e.cycles(8 + dest.address_calculation_cycles(OpSize::Byte));
            }
            e.code.push(0x4B1403F4);
            let (m, r) = ea(dest);
            e.write_operand(m, r, 1);
        }
        Instruction::LoadEffectiveAddress(source, register) => {
            let (m, r) = ea(source);
            e.address(m, r, 4);
            e.write_reg(u32::from(register.0) + 8, 21, 4);
        }
        Instruction::PushEffectiveAddress(source) => {
            let (m, r) = ea(source);
            e.address(m, r, 4);
            e.mov(20, 21);
            e.push();
        }
        Instruction::Jump(source) | Instruction::JumpToSubroutine(source) => {
            let (m, r) = ea(source);
            e.address(m, r, 4);
            e.reg(16, 24);
            e.mov(1, 21);
            e.call(64);
            if matches!(instruction, Instruction::JumpToSubroutine(..)) {
                e.mov(20, 24);
                e.push();
            }
        }
        Instruction::ExchangeData(a, b) => {
            exchange(&mut e, a.0.into(), b.0.into());
        }
        Instruction::ExchangeAddress(a, b) => {
            exchange(&mut e, u32::from(a.0) + 8, u32::from(b.0) + 8);
        }
        Instruction::ExchangeDataAddress(a, b) => {
            exchange(&mut e, a.0.into(), u32::from(b.0) + 8);
        }
        Instruction::Link(register) => {
            e.fetch();
            e.code.push(0x13003C16);
            e.reg(u32::from(register.0) + 8, 20);
            if register.0 == 7 {
                e.code.push(0x51001294);
            }
            e.push();
            e.reg(15, 21);
            e.write_reg(u32::from(register.0) + 8, 21, 4);
            e.code.push(0x0B1602B5);
            e.write_reg(15, 21, 4);
        }
        Instruction::Unlink(register) => {
            e.reg(u32::from(register.0) + 8, 20);
            e.write_reg(15, 20, 4);
            e.pop(4);
            e.write_reg(u32::from(register.0) + 8, 20, 4);
        }
        Instruction::Return { restore_ccr } => {
            if restore_ccr {
                e.pop(2);
                e.mov(1, 20);
                e.call(40);
            }
            e.pop(4);
            e.mov(1, 20);
            e.call(64);
        }
        _ => unreachable!(),
    }
    Some(e.finish())
}
fn exchange(e: &mut Emitter, a: u32, b: u32) {
    e.reg(a, 20);
    e.reg(b, 21);
    e.write_reg(a, 21, 4);
    e.write_reg(b, 20, 4);
}
