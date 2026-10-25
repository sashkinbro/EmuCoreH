//! Experimental single-instruction ARM64 bodies; no guest or serialized host resources.

#[cfg(any(test, all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"))))]
fn emit(opcode: u16) -> Option<Vec<u32>> {
    let n = u32::from((opcode >> 8) & 15);
    let m = u32::from((opcode >> 4) & 15);
    let load = |r: u32, scratch: u32| 0xB9400000u32 | (r << 10) | scratch;
    let store = 0xB9000001 | (n << 10);
    let mut code = match opcode & 0xF000 {
        0xE000 => {
            let imm = opcode as u8 as i8 as i32 as u32;
            vec![0x52800001 | ((imm & 0xFFFF) << 5), 0x72A00001 | ((imm >> 16) << 5)]
        }
        0x7000 => {
            let imm = opcode as u8 as i8 as i32;
            let instruction = if imm < 0 { 0x51000021 } else { 0x11000021 };
            vec![load(n, 1), instruction | (imm.unsigned_abs() << 10)]
        }
        _ => match opcode & 0xF00F {
            0x6003 => vec![load(m, 1)],
            0x300C | 0x2009 | 0x200A | 0x200B => {
                let op = match opcode & 15 {
                    0xC => 0x0B020021, // add w1, w1, w2 (wraps, no flags)
                    0x9 => 0x0A020021, // and
                    0xA => 0x4A020021, // eor
                    0xB => 0x2A020021, // orr
                    _ => unreachable!(),
                };
                vec![load(n, 1), load(m, 2), op]
            }
            _ if opcode == 0x0009 => return Some(vec![0xD65F03C0]),
            _ => return None,
        },
    };
    code.extend([store, 0xD65F03C0]);
    Some(code)
}

/// Host execution context. Keep separate from `Sh2`: clone and savestate operations never
/// copy executable pointers. Unsupported hosts and allocation failures use the interpreter.
/// This intentionally compiles one fetched opcode at a time, not guest memory blocks.
#[derive(Default)]
pub struct NativeExecutor {
    #[cfg(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux")))]
    cache: std::collections::HashMap<u16, executable::Body>,
    disabled: bool,
    native_instructions: u64,
}

impl NativeExecutor {
    /// Count of instructions actually executed in generated ARM64 code, not cache hits or attempts.
    #[must_use]
    pub fn native_instructions(&self) -> u64 {
        self.native_instructions
    }

    /// Disable translation (also useful when executable memory is forbidden by the host).
    pub fn disable(&mut self) {
        self.disabled = true;
    }

    pub(crate) fn execute(&mut self, gpr: &mut [u32; 16], opcode: u16) -> bool {
        if self.disabled {
            return false;
        }
        #[cfg(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux")))]
        {
            if !self.cache.contains_key(&opcode) {
                let Some(code) = emit(opcode) else {
                    return false;
                };
                let Some(body) = executable::Body::publish(&code) else {
                    self.disabled = true;
                    return false;
                };
                // Bounded per-executor memory, including 16 KiB-page Android devices.
                if self.cache.len() == 256 {
                    self.cache.clear();
                }
                self.cache.insert(opcode, body);
            }
            self.cache[&opcode].execute(gpr);
            self.native_instructions += 1;
            true
        }
        #[cfg(not(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"))))]
        {
            let _ = (gpr, opcode);
            false
        }
    }
}

#[cfg(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux")))]
mod executable {
    use std::ffi::c_void;
    use std::ptr::NonNull;

    unsafe extern "C" {
        fn getpagesize() -> i32;
        fn mmap(
            addr: *mut c_void,
            len: usize,
            prot: i32,
            flags: i32,
            fd: i32,
            offset: isize,
        ) -> *mut c_void;
        fn mprotect(addr: *mut c_void, len: usize, prot: i32) -> i32;
        fn munmap(addr: *mut c_void, len: usize) -> i32;
    }

    pub(super) struct Body {
        ptr: NonNull<c_void>,
        len: usize,
    }

    impl Body {
        pub(super) fn publish(code: &[u32]) -> Option<Self> {
            // SAFETY: no arguments and no preconditions for getpagesize.
            let page = usize::try_from(unsafe { getpagesize() }).ok()?;
            if !page.is_power_of_two() {
                return None;
            }
            let bytes = code.len().checked_mul(4)?;
            let len = bytes.checked_add(page - 1)? & !(page - 1);
            if len == 0 {
                return None;
            }
            // Linux/Android MAP_PRIVATE | MAP_ANONYMOUS, PROT_READ | PROT_WRITE.
            // SAFETY: anonymous mapping, no backing file, kernel selects its address.
            let raw = unsafe { mmap(std::ptr::null_mut(), len, 3, 0x22, -1, 0) };
            if raw as isize == -1 {
                return None;
            }
            let Some(ptr) = NonNull::new(raw) else {
                // A zero mapping cannot be represented as a Rust function pointer.
                unsafe {
                    munmap(raw, len);
                }
                return None;
            };
            let body = Self { ptr, len };
            // SAFETY: mapping is writable and at least code.len()*4 bytes long.
            unsafe {
                std::ptr::copy_nonoverlapping(code.as_ptr(), raw.cast::<u32>(), code.len());
            }
            // Publish only RX, never RWX. Failure drops/unmaps the unpublished mapping.
            if unsafe { mprotect(raw, len, 5) } != 0 {
                return None;
            }
            // SAFETY: cache maintenance spans this live RX mapping. AAPCS64 permits x0-x17
            // scratch; emitted leaf bodies use x0 (GPR array) and w1/w2 only, preserve
            // SP and x19-x30, and RET through the caller's x30. No Rust struct offsets.
            unsafe {
                flush(raw as usize, bytes);
            }
            Some(body)
        }

        pub(super) fn execute(&self, gpr: &mut [u32; 16]) {
            // SAFETY: publication verifies live RX memory containing only our leaf emitter's
            // code. x0 addresses a uniquely borrowed contiguous array of 16 four-byte u32s;
            // all generated loads/stores use array indices 0..15 and never retain the pointer.
            unsafe {
                let run: unsafe extern "C" fn(*mut u32) = std::mem::transmute(self.ptr.as_ptr());
                run(gpr.as_mut_ptr());
            }
        }
    }

    impl Drop for Body {
        fn drop(&mut self) {
            // SAFETY: owns this mapping exclusively; no generated call can outlive &self.
            unsafe {
                munmap(self.ptr.as_ptr(), self.len);
            }
        }
    }

    unsafe fn flush(start: usize, bytes: usize) {
        use std::arch::asm;
        let ctr: usize;
        // ARM ARM cache publication sequence, using runtime D/I cache line sizes.
        unsafe {
            asm!("mrs {ctr}, ctr_el0", ctr = out(reg) ctr, options(nostack, preserves_flags));
            let dline = 4 << ((ctr >> 16) & 15);
            let iline = 4 << (ctr & 15);
            for addr in ((start & !(dline - 1))..(start + bytes)).step_by(dline) {
                asm!("dc cvau, {addr}", addr = in(reg) addr, options(nostack, preserves_flags));
            }
            asm!("dsb ish", options(nostack, preserves_flags));
            for addr in ((start & !(iline - 1))..(start + bytes)).step_by(iline) {
                asm!("ic ivau, {addr}", addr = in(reg) addr, options(nostack, preserves_flags));
            }
            asm!("dsb ish", "isb", options(nostack, preserves_flags));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn emits_register_abi_and_rejects_memory_and_flag_operations() {
        // x0 points directly at [u32; 16]; w1/w2 are caller-saved scratch registers.
        assert_eq!(emit(0x6123), Some(vec![0xB9400801, 0xB9000401, 0xD65F03C0]));
        assert_eq!(emit(0x0009), Some(vec![0xD65F03C0]));
        for opcode in [0x6122, 0x312E, 0xC900, 0xA001, 0xFFFF] {
            assert_eq!(emit(opcode), None, "{opcode:04X}");
        }
    }
}

#[cfg(test)]
mod differential;
