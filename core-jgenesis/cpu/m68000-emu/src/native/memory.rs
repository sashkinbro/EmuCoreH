#![cfg_attr(not(any(test,all(target_arch="aarch64",target_endian="little",any(target_os="android",target_os="linux")))),allow(dead_code))]
//! MOVE lowering with emitted effective-address arithmetic. Callbacks are limited
//! to primitive architectural register access, prefetch, bus access and CCR commit.
#[repr(C)]
pub(crate) struct Context {
    pub fetch: unsafe extern "C" fn(*mut Context) -> u32,
    pub read: unsafe extern "C" fn(*mut Context, u32, u32) -> u32,
    pub write: unsafe extern "C" fn(*mut Context, u32, u32, u32),
    pub read_register: unsafe extern "C" fn(*mut Context, u32) -> u32,
    pub write_register: unsafe extern "C" fn(*mut Context, u32, u32, u32),
    pub set_ccr: unsafe extern "C" fn(*mut Context, u32),
    pub error: u32,
    pub ccr: u32,
    pub opaque: *mut std::ffi::c_void,
    pub jump: unsafe extern "C" fn(*mut Context, u32),
    pub cycles: u32,
    pub allow_tas_writes: u32,
    pub status: unsafe extern "C" fn(*mut Context, u32, u32) -> u32,
    pub raise: unsafe extern "C" fn(*mut Context, u32, u32),
    pub supervisor: u32,
}
pub(super) struct Emitter {
    pub(super) code: Vec<u32>,
    errors: Vec<usize>,
}
impl Emitter {
    pub(super) fn new() -> Self {
        Self {
            code: vec![0xA9BF7BF3, 0xA9BF57F4, 0xA9BF5FF6, 0xA9BF67F8, 0xAA0003F3],
            errors: vec![],
        }
    }
    pub(super) fn finish(mut self) -> Vec<u32> {
        let exit = self.code.len();
        for b in self.errors {
            self.code[b] |= ((exit - b) as u32) << 5;
        }
        self.code.extend([0xA8C167F8, 0xA8C15FF6, 0xA8C157F4, 0xA8C17BF3, 0xD65F03C0]);
        self.code
    }

    pub(super) fn imm(&mut self, r: u32, v: u32) {
        self.code.extend([0x52800000 | ((v & 65535) << 5) | r, 0x72A00000 | ((v >> 16) << 5) | r]);
    }
    fn mov(&mut self, to: u32, from: u32) {
        self.code.push(0x2A0003E0 | (from << 16) | to);
    }
    fn call(&mut self, offset: u32) {
        self.code.extend([
            0xAA1303E0,
            0xF9400000 | ((offset / 8) << 10) | (19 << 5) | 16,
            0xD63F0200,
            0xB9400000 | (12 << 10) | (19 << 5) | 9,
        ]);
        self.errors.push(self.code.len());
        self.code.push(0x35000009);
    }
    pub(super) fn reg(&mut self, r: u32, out: u32) {
        self.imm(1, r);
        self.call(24);
        self.mov(out, 0);
    }
    pub(super) fn write_reg(&mut self, r: u32, value: u32, size: u32) {
        self.imm(1, r);
        self.mov(2, value);
        self.imm(3, size);
        self.call(32);
    }
    fn fetch(&mut self) {
        self.call(0);
    }
    // Result address w21. Only emitted A64 performs EA addition/subtraction,
    // sign extension and index selection; callbacks never resolve an EA.
    fn address(&mut self, mode: u16, reg: u16, size: u32) {
        let a = u32::from(reg) + 8;
        match mode {
            2 | 3 => self.reg(a, 21),
            4 => {
                self.reg(a, 21);
                let step = if size == 1 && reg == 7 { 2 } else { size };
                self.code.push(0x510002B5 | (step << 10));
                self.write_reg(a, 21, 4);
            }
            5 => {
                self.fetch();
                self.mov(22, 0);
                self.reg(a, 21);
                self.code.extend([0x13003EC0, 0x0B0002B5]);
            }
            6 => {
                self.fetch();
                self.mov(22, 0);
                self.reg(a, 21);
                self.index();
            }
            7 => match reg {
                0 => {
                    self.fetch();
                    self.code.push(0x13003C15);
                }
                1 => {
                    self.fetch();
                    self.code.push(0x53103C15);
                    self.fetch();
                    self.code.push(0x2A0002B5);
                }
                2 => {
                    self.reg(16, 21);
                    self.fetch();
                    self.code.extend([0x13003C00, 0x0B0002B5]);
                }
                3 => {
                    self.reg(16, 21);
                    self.fetch();
                    self.mov(22, 0);
                    self.index();
                }
                _ => unreachable!(),
            },
            _ => unreachable!(),
        }
    }
    fn index(&mut self) {
        self.code.push(0x53000000 | (12 << 16) | (31 << 10) | (22 << 5) | 1);
        self.call(24);
        self.code.extend([
            0x37000000 | (11 << 19) | (2 << 5) | 22,
            0x13003C00,
            0x0B0002B5,
            0x13001EC0,
            0x0B0002B5,
        ]);
    }
    fn post(&mut self, mode: u16, reg: u16, size: u32) {
        if mode == 3 {
            let step = if size == 1 && reg == 7 { 2 } else { size };
            self.code.push(0x110002B5 | (step << 10));
            self.write_reg(u32::from(reg) + 8, 21, 4);
        }
    }
    fn read_operand(&mut self, mode: u16, reg: u16, size: u32) {
        if mode == 8 {
            self.imm(20, u32::from(reg));
        } else if mode <= 1 {
            self.reg(u32::from(reg) + 8 * u32::from(mode), 20);
        } else if mode == 7 && reg == 4 {
            self.fetch();
            self.mov(20, 0);
            if size == 4 {
                self.code.push(0x53103E94);
                self.fetch();
                self.code.push(0x2A000294);
            }
        } else {
            self.address(mode, reg, size);
            if mode == 3 {
                self.mov(23, 21);
                self.post(mode, reg, size);
                self.mov(21, 23);
            }
            self.mov(1, 21);
            self.imm(2, size);
            self.call(8);
            self.mov(20, 0);
        }
    }
    fn write_operand(&mut self, mode: u16, reg: u16, size: u32) {
        if mode <= 1 {
            self.write_reg(u32::from(reg) + 8 * u32::from(mode), 20, size);
        } else if mode == 4 && size == 4 {
            // MOVE.L to -(An) writes low word first, with each decrement
            // architecturally visible before its bus write (including faults).
            self.reg(u32::from(reg) + 8, 21);
            for high in [false, true] {
                self.code.push(0x51000AB5);
                self.write_reg(u32::from(reg) + 8, 21, 4);
                self.mov(1, 21);
                self.mov(2, 20);
                if high {
                    self.code.push(0x53107E82);
                }
                self.imm(3, 2);
                self.call(16);
            }
        } else {
            self.address(mode, reg, size);
            self.mov(1, 21);
            self.mov(2, 20);
            self.imm(3, size);
            self.call(16);
            self.post(mode, reg, size);
        }
    }
}
use crate::core::{AddressingMode, OpSize, instructions::Instruction};
#[derive(Clone, Copy, PartialEq)]
pub(crate) enum Op {
    Move,
    Add,
    Sub,
    Cmp,
    And,
    Or,
    Xor,
    Clear,
    Not,
    Test,
    Neg,
}
pub(crate) fn plan(opcode: u16) -> Option<(Op, OpSize, AddressingMode, AddressingMode)> {
    Some(match Instruction::decode_from(opcode) {
        Instruction::Move { size, source, dest } => (Op::Move, size, source, dest),
        Instruction::Add { size, source, dest, with_extend: false } => {
            (Op::Add, size, source, dest)
        }
        Instruction::Subtract { size, source, dest, with_extend: false } => {
            (Op::Sub, size, source, dest)
        }
        Instruction::Compare { size, source, dest } => (Op::Cmp, size, source, dest),
        Instruction::And { size, source, dest } => (Op::And, size, source, dest),
        Instruction::Or { size, source, dest } => (Op::Or, size, source, dest),
        Instruction::ExclusiveOr { size, source, dest } => (Op::Xor, size, source, dest),
        Instruction::Clear(size, dest) => (Op::Clear, size, AddressingMode::Quick(0), dest),
        Instruction::Not(size, dest) => (Op::Not, size, AddressingMode::Quick(0), dest),
        Instruction::Test(size, dest) => (Op::Test, size, AddressingMode::Quick(0), dest),
        Instruction::Negate { size, dest, with_extend: false } => {
            (Op::Neg, size, AddressingMode::Quick(0), dest)
        }
        _ => return None,
    })
}
fn ea(a: AddressingMode) -> (u16, u16) {
    use AddressingMode::*;
    match a {
        DataDirect(r) => (0, r.0.into()),
        AddressDirect(r) => (1, r.0.into()),
        AddressIndirect(r) => (2, r.0.into()),
        AddressIndirectPostincrement(r) => (3, r.0.into()),
        AddressIndirectPredecrement(r) => (4, r.0.into()),
        AddressIndirectDisplacement(r) => (5, r.0.into()),
        AddressIndirectIndexed(r) => (6, r.0.into()),
        AbsoluteShort => (7, 0),
        AbsoluteLong => (7, 1),
        PcRelativeDisplacement => (7, 2),
        PcRelativeIndexed => (7, 3),
        Immediate => (7, 4),
        Quick(v) => (8, v.into()),
    }
}
impl Emitter {
    pub(super) fn logical_flags(&mut self, size: u32) {
        self.mov(1, 20);
        if size != 4 {
            self.code.push(if size == 1 { 0x13001C21 } else { 0x13003C21 });
        }
        self.code.extend([
            0xB9403662, 0x121C0042, 0x7100003F, 0x1A9F17E3, 0x531E7463, 0x531F7C24, 0x2A040C63,
            0x2A030041,
        ]);
        self.call(40);
    }
    fn alu(&mut self, op: Op, size: u32, addr: bool) {
        self.mov(1, 20);
        self.mov(2, 24);
        if addr && size == 2 {
            self.code.push(0x13003C42);
        }
        let size = if addr { 4 } else { size };
        let arithmetic = matches!(op, Op::Add | Op::Sub | Op::Cmp);
        if arithmetic && size != 4 {
            let shift = 32 - size * 8;
            for r in [1, 2] {
                self.code.push(
                    0x53000000 | (((32 - shift) & 31) << 16) | ((31 - shift) << 10) | (r << 5) | r,
                );
            }
        }
        self.code.push(match op {
            Op::Add => 0x2B020021,
            Op::Sub | Op::Cmp => 0x6B020021,
            Op::And => 0x0A020021,
            Op::Or => 0x2A020021,
            Op::Xor => 0x4A020021,
            _ => unreachable!(),
        });
        if arithmetic && (!addr || op == Op::Cmp) {
            let carry = if op == Op::Add { 3 } else { 2 };
            self.code.extend([
                0x1A9F07E3 | (carry << 12),
                0x1A9F77E4,
                0x2A040463,
                0x1A9F17E4,
                0x2A040863,
                0x1A9F57E4,
                0x2A040C63,
            ]);
            if op == Op::Cmp {
                self.code.extend([0xB9403662, 0x121C0042, 0x2A020063]);
            } else {
                self.code.extend([0x12000064, 0x2A041063]);
            }
        }
        if arithmetic && size != 4 {
            let shift = 32 - size * 8;
            self.code.push(0x53000021 | (shift << 16) | (31 << 10));
        }
        self.mov(20, 1);
        if arithmetic {
            if !addr || op == Op::Cmp {
                self.mov(1, 3);
                self.call(40);
            }
        } else {
            self.logical_flags(size);
        }
    }
    fn write_resolved(&mut self, mode: u16, reg: u16, size: u32) {
        if mode <= 1 {
            self.write_reg(u32::from(reg) + 8 * u32::from(mode), 20, size);
        } else {
            self.mov(1, 21);
            self.mov(2, 20);
            self.imm(3, size);
            self.call(16);
        }
    }
}
pub(crate) fn emit(opcode: u16) -> Option<Vec<u32>> {
    if let Some(code) = divide::emit(opcode) {
        return Some(code);
    }
    if let Some(code) = special::emit(opcode) {
        return Some(code);
    }
    if let Some(code) = transfer::emit(opcode) {
        return Some(code);
    }
    if let Some(code) = extended::emit(opcode) {
        return Some(code);
    }
    if let Some(code) = misc::emit(opcode) {
        return Some(code);
    }
    if let Some(code) = shift::emit(opcode) {
        return Some(code);
    }
    if let Some(code) = control::emit(opcode) {
        return Some(code);
    }
    let (op, size, source, dest) = plan(opcode)?;
    let size = match size {
        OpSize::Byte => 1,
        OpSize::Word => 2,
        OpSize::LongWord => 4,
    };
    let (sm, sr) = ea(source);
    let (dm, dr) = ea(dest);
    let mut e = Emitter {
        code: vec![0xA9BF7BF3, 0xA9BF57F4, 0xA9BF5FF6, 0xA9BF67F8, 0xAA0003F3],
        errors: vec![],
    };
    e.read_operand(sm, sr, size);
    if op == Op::Move {
        if dm != 1 {
            e.logical_flags(size);
        }
        e.write_operand(dm, dr, size);
    } else {
        e.mov(24, 20);
        // ADDA/SUBA/CMPA always read the entire destination register.
        e.read_operand(dm, dr, if dm == 1 { 4 } else { size });
        match op {
            Op::Clear => {
                e.imm(20, 0);
                e.logical_flags(size);
            }
            Op::Not => {
                e.code.push(0x2A3403F4);
                e.logical_flags(size);
            }
            Op::Test => e.logical_flags(size),
            Op::Neg => {
                e.mov(24, 20);
                e.imm(20, 0);
                e.alu(Op::Sub, size, false);
            }
            _ => e.alu(op, size, dm == 1),
        }
        if op != Op::Cmp && op != Op::Test {
            e.write_resolved(dm, dr, if dm == 1 { 4 } else { size });
        }
    }
    let exit = e.code.len();
    for b in e.errors {
        e.code[b] |= ((exit - b) as u32) << 5;
    }
    e.code.extend([0xA8C167F8, 0xA8C15FF6, 0xA8C157F4, 0xA8C17BF3, 0xD65F03C0]);
    Some(e.code)
}
#[cfg(test)]
mod tests {
    #[test]
    fn move_legal_addressing_modes_only() {
        for size in [0x1000, 0x2000, 0x3000] {
            for sm in 0..8 {
                for sr in 0..8 {
                    for dm in 0..8 {
                        for dr in 0..8 {
                            let op = size | (dr << 9) | (dm << 6) | (sm << 3) | sr;
                            let legal = matches!(
                                crate::core::instructions::Instruction::decode_from(op),
                                crate::core::instructions::Instruction::Move { .. }
                            );
                            assert_eq!(super::emit(op).is_some(), legal, "{op:04X}");
                        }
                    }
                }
            }
        }
    }
}

pub(crate) mod control;

pub(crate) mod shift;

pub(crate) mod misc;

pub(crate) mod extended;

pub(crate) mod transfer;

pub(crate) mod special;

pub(crate) mod divide;
