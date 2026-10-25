//! Experimental ARM64 instruction bodies, kept outside serialized CPU state.
#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
fn emit(opcode: u16) -> Option<Vec<u32>> {
    if opcode == 0x4E71 {
        return Some(vec![0xD65F03C0]);
    }
    let reg;
    let mut code;
    let word = opcode & 0xFFF8 == 0x4880;
    if opcode & 0xF100 == 0x7000 {
        reg = u32::from((opcode >> 9) & 7);
        let imm = opcode as u8 as i8 as i32 as u32;
        code = vec![0x52800001 | ((imm & 0xFFFF) << 5), 0x72A00001 | ((imm >> 16) << 5)];
    } else {
        reg = u32::from(opcode & 7);
        let op = match opcode & 0xFFF8 {
            0x4840 => 0x13814021, // ror w1,w1,#16
            0x4880 => 0x13001C21, // sxtb w1,w1 (write low word only)
            0x48C0 => 0x13003C21, // sxth w1,w1
            _ => return None,
        };
        code = vec![0xB9400001 | (reg << 10), op];
    }
    // x0: repr(C) Context. w1=result; w2=CCR; w3/w4 scratch.
    // Flags are computed by native instructions; preserve X, clear V/C.
    code.extend([
        if word { 0x79000001 | (reg << 11) } else { 0xB9000001 | (reg << 10) },
        0xB9402002, // ldr w2,[x0,#32]
        0x121C0042, // and w2,w2,#16
        0x7100003F, // cmp w1,#0
        0x1A9F17E3, // cset w3,eq
        0x531E7463, // lsl w3,w3,#2
        0x531F7C24, // lsr w4,w1,#31
        0x2A040C63, // orr w3,w3,w4,lsl#3
        0x2A030042, // orr w2,w2,w3
        0xB9002002, // str w2,[x0,#32]
        0xD65F03C0,
    ]);
    Some(code)
}

#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
#[repr(C)]
struct Context {
    data: [u32; 8],
    ccr: u32,
}

/// Host resources belong to this executor, never the cloneable/serialized guest CPU.
/// One fetched instruction per call preserves existing prefetch and interrupt boundaries.
#[derive(Debug, Default)]
pub struct NativeExecutor {
    #[cfg(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    ))]
    cache: std::collections::HashMap<u16, jgenesis_common::native_code::PublishedCode>,
    disabled: bool,
    native_instructions: u64,
}

impl NativeExecutor {
    #[must_use]
    pub fn native_instructions(&self) -> u64 {
        self.native_instructions
    }
    pub fn disable(&mut self) {
        self.disabled = true;
    }

    pub(crate) fn execute(&mut self, data: &mut [u32; 8], ccr: &mut u8, opcode: u16) -> bool {
        if self.disabled {
            return false;
        }
        #[cfg(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        ))]
        {
            if !self.cache.contains_key(&opcode) {
                let Some(code) = emit(opcode) else {
                    return false;
                };
                let Some(body) = jgenesis_common::native_code::PublishedCode::publish(&code) else {
                    self.disabled = true;
                    return false;
                };
                if self.cache.len() == 256 {
                    self.cache.clear();
                }
                self.cache.insert(opcode, body);
            }
            let mut ctx = Context { data: *data, ccr: u32::from(*ccr) };
            // SAFETY: our leaf emitter uses caller-saved x0..x4, preserves SP/x19..x30,
            // and accesses only Context's repr(C) array at 0..28 and u32 CCR at 32.
            // Code lives through this synchronous call and retains no guest pointers.
            unsafe {
                let run: unsafe extern "C" fn(*mut Context) =
                    std::mem::transmute(self.cache[&opcode].entry_pointer());
                run(&mut ctx);
            }
            *data = ctx.data;
            *ccr = ctx.ccr as u8;
            self.native_instructions += 1;
            true
        }
        #[cfg(not(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        )))]
        {
            let _ = (data, ccr, opcode);
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn emits_only_register_operations() {
        for r in 0..8 {
            for imm in 0..256 {
                assert!(emit(0x7000 | (r << 9) | imm).is_some());
            }
            for op in [0x4840, 0x4880, 0x48C0] {
                assert!(emit(op | r).is_some());
            }
        }
        assert_eq!(emit(0x4E71), Some(vec![0xD65F03C0]));
        for op in [0x7100, 0x4E72, 0x4E75, 0x6000, 0x3010, 0x48D0, 0x4848] {
            assert_eq!(emit(op), None, "{op:04X}");
        }
    }
}

#[cfg(all(test, feature = "bincode"))]
mod differential;
