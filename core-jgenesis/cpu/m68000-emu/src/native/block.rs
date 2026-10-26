//! Learned whole-instruction native blocks. Each body is inlined A64; the two
//! callbacks per instruction retain the CPU shell and backend device scheduling.
#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
pub(super) fn emit(trace: &[(u32, u16)]) -> Option<Vec<u32>> {
    if trace.len() < 2 || trace.len() > 16 {
        return None;
    }
    let mut code = vec![0xA9BF7BF3, 0xA9BF57F4, 0xA9BF5FF6, 0xA9BF67F8, 0xA9BF6FFA, 0xAA0003FA];
    let mut exits = vec![];
    for &(pc, opcode) in trace {
        let body = body(opcode)?;
        code.extend([
            0xAA1A03E0,
            0x52800001 | ((pc & 65535) << 5),
            0x72A00001 | ((pc >> 16) << 5),
            0x52800002 | (u32::from(opcode) << 5),
            0xF9400350,
            0xD63F0200,
        ]);
        exits.push(code.len());
        code.push(0xB4000000);
        code.push(0xAA0003F3);
        debug_assert_eq!(&body[..5], &[0xA9BF7BF3, 0xA9BF57F4, 0xA9BF5FF6, 0xA9BF67F8, 0xAA0003F3]);
        code.extend_from_slice(&body[5..body.len() - 5]);
        code.extend([0xAA1A03E0, 0xF9400750, 0xD63F0200]);
        exits.push(code.len());
        code.push(0x35000000);
    }
    let end = code.len();
    for i in exits {
        code[i] |= ((end - i) as u32) << 5;
    }
    code.extend([0xA8C16FFA, 0xA8C167F8, 0xA8C15FF6, 0xA8C157F4, 0xA8C17BF3, 0xD65F03C0]);
    Some(code)
}
#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
fn body(opcode: u16) -> Option<Vec<u32>> {
    if let Some(code) = super::memory::emit(opcode) {
        return Some(code);
    }
    use crate::core::instructions::Instruction;
    let mut e = super::memory::Emitter::new();
    match Instruction::decode_from(opcode) {
        Instruction::NoOp => {}
        Instruction::MoveQuick(value, register) => {
            e.imm(20, value as i32 as u32);
            e.write_reg(register.0.into(), 20, 4);
            e.logical_flags(4);
        }
        Instruction::Swap(register) => {
            e.reg(register.0.into(), 20);
            e.code.push(0x13944294);
            e.write_reg(register.0.into(), 20, 4);
            e.logical_flags(4);
        }
        Instruction::Extend(size, register) => {
            e.reg(register.0.into(), 20);
            let bytes = if size == crate::core::OpSize::Word {
                e.code.push(0x13001E94);
                2
            } else {
                e.code.push(0x13003E94);
                4
            };
            e.write_reg(register.0.into(), 20, bytes);
            e.logical_flags(bytes);
        }
        _ => return None,
    }
    Some(e.finish())
}
#[cfg(test)]
mod tests {
    #[test]
    fn every_legal_body_has_inline_block_abi() {
        use crate::core::instructions::Instruction;
        for op in 0..=u16::MAX {
            let legal = !matches!(Instruction::decode_from(op), Instruction::Illegal { .. })
                || op == 0x4AFC
                || op >> 12 == 0xA
                || op >> 12 == 0xF;
            if legal {
                let b = super::body(op).expect("legal body");
                assert_eq!(&b[..5], &[0xA9BF7BF3, 0xA9BF57F4, 0xA9BF5FF6, 0xA9BF67F8, 0xAA0003F3]);
            }
        }
    }
}
