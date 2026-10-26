//! Native DIVS/DIVU/CHK, including data-dependent timing, overflow and exception
//! exits. Only prefetch/bus/architectural register primitives call Rust.
use super::*;
pub(crate) fn base_cycles(opcode: u16) -> Option<u32> {
    Some(match Instruction::decode_from(opcode) {
        Instruction::DivideSigned(..) | Instruction::DivideUnsigned(..) => 0,
        Instruction::CheckRegister(_, source) => {
            10 + source.address_calculation_cycles(OpSize::Word)
        }
        _ => return None,
    })
}
impl Emitter {
    fn b_cond(&mut self, condition: u32) -> usize {
        let i = self.code.len();
        self.code.push(0x54000000 | condition);
        i
    }
    fn store_cycles(&mut self) {
        self.code.push(0xB9004A69);
    }
    fn flags_x_plus(&mut self, flags: u32) {
        self.code.extend([0xB9403661, 0x121C0021]);
        self.imm(2, flags);
        self.op(0x2A000000, 1, 1, 2);
        self.call(40);
    }
    fn abs(&mut self, out: u32, input: u32, scratch: u32) {
        self.code.push(0x131F7C00 | (input << 5) | scratch);
        self.op(0x4A000000, out, input, scratch);
        self.op(0x4B000000, out, out, scratch);
    }
    fn popcount(&mut self, input: u32, count: u32) {
        self.imm(count, 0);
        let done = self.cbz(input);
        let start = self.code.len();
        self.code.push(0x51000400 | (input << 5) | 3);
        self.op(0x0A000000, input, input, 3);
        self.code.push(0x11000400 | (count << 5) | count);
        let delta = start as i32 - self.code.len() as i32;
        self.code.push(0x35000000 | input | (((delta as u32) & 0x7FFFF) << 5));
        self.patch(done);
    }
    fn divs_cycles(&mut self, ea: u32) {
        self.abs(1, 22, 2);
        self.ubfx(1, 1, 1, 15);
        self.popcount(1, 9);
        self.code.push(0x531F7929);
        self.imm(2, 150 + ea);
        self.op(0x4B000000, 9, 2, 9);
        self.ubfx(1, 25, 31, 1);
        self.ubfx(2, 24, 31, 1);
        self.code.extend([0x0B010529, 0x0B020529, 0x52000043, 0x0A030023, 0x0B030929]);
        self.store_cycles();
    }
    fn divu_cycles(&mut self, ea: u32) {
        self.code.push(0x53103F18);
        self.imm(9, 76 + ea);
        self.imm(5, 15);
        let start = self.code.len();
        self.ubfx(1, 25, 31, 1);
        self.code.push(0x531F7B39);
        self.op(0x6B000000, 31, 25, 24);
        self.cset(2, 3);
        self.code.push(0x52000043);
        self.op(0x2A000000, 3, 3, 1);
        let no_sub = self.cbz(3);
        self.op(0x4B000000, 25, 25, 24);
        self.patch(no_sub);
        self.code.push(0x52000021);
        self.code.push(0x0B010529);
        self.op(0x0A000000, 2, 2, 1);
        self.code.push(0x0B020529);
        self.code.push(0x510004A5);
        let delta = start as i32 - self.code.len() as i32;
        self.code.push(0x35000005 | (((delta as u32) & 0x7FFFF) << 5));
        self.store_cycles();
    }
    fn div_normal(&mut self, signed: bool, reg: u32, ea: u32) {
        self.mov(20, 22);
        self.logical_flags(2);
        self.mask_width(20, 16);
        self.code.push(0x2A174294);
        self.write_reg(reg, 20, 4);
        if signed {
            self.divs_cycles(ea);
        } else {
            self.divu_cycles(ea);
        }
    }
    fn div_overflow(&mut self, signed: bool, ea: u32) {
        self.flags_x_plus(10);
        if signed {
            self.abs(1, 25, 3);
            self.code.push(0x13107C21);
            self.abs(2, 24, 3);
            self.op(0x6B000000, 31, 1, 2);
            // Match the interpreter timing model: wrapping_abs(INT_MIN) is
            // negative and its arithmetic >>16 fails the signed early-overflow test.
            let late = self.b_cond(11);
            self.ubfx(9, 25, 31, 1);
            self.code.push(0x531F7929);
            self.code.push(0x11000129 | ((16 + ea) << 10));
            self.store_cycles();
            let done = self.b();
            self.patch(late);
            self.divs_cycles(ea);
            self.patch(done);
        } else {
            self.cycles(10 + ea);
        }
    }
}
pub(super) fn emit(opcode: u16) -> Option<Vec<u32>> {
    base_cycles(opcode)?;
    let mut e = Emitter::new();
    match Instruction::decode_from(opcode) {
        Instruction::DivideSigned(reg, source) | Instruction::DivideUnsigned(reg, source) => {
            let signed = matches!(Instruction::decode_from(opcode), Instruction::DivideSigned(..));
            let ea_cycles = source.address_calculation_cycles(OpSize::Word);
            let (m, r) = ea(source);
            e.read_operand(m, r, 2);
            e.mov(24, 20);
            e.code.push(if signed { 0x13003F18 } else { 0x53003F18 });
            e.reg(reg.0.into(), 20);
            e.mov(25, 20);
            let zero = e.cbz(24);
            e.code.push(if signed { 0x1AD80E96 } else { 0x1AD80A96 });
            e.code.push(0x1B18D2D7);
            let overflow = if signed {
                e.code.push(0x13003EC1);
                e.op(0x6B000000, 31, 1, 22);
                e.b_cond(1)
            } else {
                e.code.push(0x53107EC1);
                let i = e.code.len();
                e.code.push(0x35000001);
                i
            };
            e.div_normal(signed, reg.0.into(), ea_cycles);
            let normal_done = e.b();
            e.patch(overflow);
            e.div_overflow(signed, ea_cycles);
            let overflow_done = e.b();
            e.patch(zero);
            e.flags_x_plus(0);
            e.raise(3, ea_cycles);
            e.patch(normal_done);
            e.patch(overflow_done);
        }
        Instruction::CheckRegister(reg, source) => {
            let ea_cycles = source.address_calculation_cycles(OpSize::Word);
            let (m, r) = ea(source);
            e.read_operand(m, r, 2);
            e.code.push(0x13003E98);
            e.reg(reg.0.into(), 20);
            e.code.push(0x13003E94);
            e.code.push(0xB9403661);
            e.imm(2, 24);
            e.op(0x0A000000, 1, 1, 2);
            e.call(40);
            e.op(0x6B000000, 31, 20, 24);
            let not_high = e.b_cond(13);
            e.ubfx(1, 20, 31, 1);
            e.code.push(0x531D7021);
            e.code.push(0xB9403662);
            e.code.push(0x121C0042);
            e.op(0x2A000000, 1, 1, 2);
            e.call(40);
            e.raise(4, ea_cycles + 8);
            e.patch(not_high);
            e.cmp_imm(20, 0);
            let nonnegative = e.b_cond(5);
            e.flags_x_plus(8);
            e.raise(4, ea_cycles + 10);
            e.patch(nonnegative);
        }
        _ => unreachable!(),
    }
    Some(e.finish())
}
