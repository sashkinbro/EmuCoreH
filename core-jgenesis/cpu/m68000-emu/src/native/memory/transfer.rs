//! MOVEM/MOVEP native transfer sequences, including documented bus ordering,
//! spurious final MOVEM reads and partial register changes on bus faults.
use super::*;
use crate::core::instructions::Direction;
pub(crate) fn base_cycles(opcode: u16) -> Option<u32> {
    Some(match Instruction::decode_from(opcode) {
        Instruction::MoveMultiple(..) => 0,
        Instruction::MovePeripheral(size, ..) => {
            if size == OpSize::Word {
                16
            } else {
                24
            }
        }
        _ => return None,
    })
}
pub(super) fn emit(opcode: u16) -> Option<Vec<u32>> {
    base_cycles(opcode)?;
    let mut e = Emitter::new();
    match Instruction::decode_from(opcode) {
        Instruction::MovePeripheral(size, d, a, direction) => {
            let size = if size == OpSize::Word { 2 } else { 4 };
            e.address(5, a.0.into(), size);
            if direction == Direction::RegisterToMemory {
                e.reg(d.0.into(), 20);
                for byte in 0..size {
                    e.mov(1, 21);
                    let shift = (size - byte - 1) * 8;
                    e.code.push(0x53000000 | (shift << 16) | (31 << 10) | (20 << 5) | 2);
                    e.imm(3, 1);
                    e.call(16);
                    e.code.push(0x11000AB5);
                }
            } else {
                e.imm(20, 0);
                for _ in 0..size {
                    e.mov(1, 21);
                    e.imm(2, 1);
                    e.call(8);
                    e.code.extend([0x53185E94, 0x2A000294, 0x11000AB5]);
                }
                e.write_reg(d.0.into(), 20, size);
            }
        }
        Instruction::MoveMultiple(size, addressing, direction) => {
            let bytes = if size == OpSize::Word { 2 } else { 4 };
            e.fetch();
            e.mov(24, 0);
            let (m, r) = ea(addressing);
            if m == 4 {
                e.reg(u32::from(r) + 8, 21);
            } else {
                e.address(m, r, bytes);
            }
            e.imm(25, 0);
            for bit in 0..16 {
                let skip = e.code.len();
                e.code.push(0x36000018 | (bit << 19));
                let reg = if m == 4 { 15 - bit } else { bit };
                if direction == Direction::RegisterToMemory {
                    if m == 4 {
                        if bytes == 2 {
                            e.code.push(0x51000AB5);
                            e.reg(reg, 20);
                            e.mov(1, 21);
                            e.mov(2, 20);
                            e.imm(3, 2);
                            e.call(16);
                        } else {
                            e.reg(reg, 20);
                            for high in [false, true] {
                                e.code.push(0x51000AB5);
                                e.mov(1, 21);
                                e.mov(2, 20);
                                if high {
                                    e.code.push(0x53107E82);
                                }
                                e.imm(3, 2);
                                e.call(16);
                            }
                        }
                    } else {
                        e.reg(reg, 20);
                        e.mov(1, 21);
                        e.mov(2, 20);
                        e.imm(3, bytes);
                        e.call(16);
                        e.code.push(0x110002B5 | (bytes << 10));
                    }
                } else {
                    if m == 3 {
                        e.code.push(0x11000AB7);
                        e.write_reg(u32::from(r) + 8, 23, 4);
                    }
                    e.mov(1, 21);
                    e.imm(2, bytes);
                    e.call(8);
                    e.mov(20, 0);
                    if bytes == 2 {
                        e.code.push(0x13003E94);
                    }
                    if !(m == 3 && reg == u32::from(r) + 8) {
                        e.write_reg(reg, 20, 4);
                    }
                    e.code.push(0x110002B5 | (bytes << 10));
                }
                e.code.push(0x11000739);
                e.patch(skip);
            }
            if direction == Direction::MemoryToRegister {
                e.mov(1, 21);
                e.imm(2, 2);
                e.call(8);
            }
            if m == 3 || m == 4 {
                e.write_reg(u32::from(r) + 8, 21, 4);
            }
            let shift = if bytes == 2 { 2 } else { 3 };
            e.code.push(
                0x53000000 | (((32 - shift) & 31) << 16) | ((31 - shift) << 10) | (25 << 5) | 9,
            );
            let base = if m == 4 {
                8
            } else {
                (if direction == Direction::MemoryToRegister { 8 } else { 4 })
                    + addressing.address_calculation_cycles(OpSize::Word)
            };
            e.code.push(0x11000129 | (base << 10));
            e.code.push(0xB9004A69);
        }
        _ => unreachable!(),
    }
    Some(e.finish())
}
