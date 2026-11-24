//! Storage for compiled blocks.
//!
//! On aarch64 Linux/Android (feature `jit-arm64`): anonymous mappings in 1 MB chunks, written
//! while RW and published RX (never RWX) with the architectural cache maintenance sequence. On
//! other hosts in tests: the code words are kept in memory and executed by the AArch64 simulator.
//! A store is reset wholesale when full; blocks drop their entries first.

use super::native::Ctx;

/// Whether this build can run compiled blocks
pub(crate) const AVAILABLE: bool = cfg!(any(
    test,
    all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"), feature = "jit-arm64")
));

#[cfg(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"), feature = "jit-arm64"))]
mod imp {
    use super::Ctx;
    use std::ffi::c_void;

    unsafe extern "C" {
        fn mmap(addr: *mut c_void, len: usize, prot: i32, flags: i32, fd: i32, offset: isize) -> *mut c_void;
        fn mprotect(addr: *mut c_void, len: usize, prot: i32) -> i32;
        fn munmap(addr: *mut c_void, len: usize) -> i32;
    }

    const CHUNK: usize = 1 << 20;
    const MAX_CHUNKS: usize = 16;
    const PAGE: usize = 4096;
    const PROT_RW: i32 = 3;
    const PROT_RX: i32 = 5;

    #[derive(Default)]
    pub(crate) struct Store {
        chunks: Vec<*mut u8>,
        used: usize,
    }

    // SAFETY: the mappings are owned exclusively by the store and only touched through `&mut`
    unsafe impl Send for Store {}
    unsafe impl Sync for Store {}

    impl Store {
        pub(crate) fn publish(&mut self, code: &[u32]) -> Option<usize> {
            let bytes = code.len() * 4;
            if bytes > CHUNK {
                return None;
            }
            if self.chunks.is_empty() || self.used + bytes > CHUNK {
                if self.chunks.len() == MAX_CHUNKS {
                    return None;
                }
                // MAP_PRIVATE | MAP_ANONYMOUS, mapped RX until written
                // SAFETY: anonymous mapping, the kernel picks the address
                let raw = unsafe { mmap(std::ptr::null_mut(), CHUNK, PROT_RX, 0x22, -1, 0) };
                if raw as isize == -1 || raw.is_null() {
                    return None;
                }
                self.chunks.push(raw.cast());
                self.used = 0;
            }
            let base = *self.chunks.last()?;
            let start = self.used;
            let first_page = start & !(PAGE - 1);
            let end = (start + bytes + PAGE - 1) & !(PAGE - 1);
            // SAFETY: the range lies in our mapping; no compiled code runs while publishing
            // (single-threaded owner), so pages may be RW temporarily
            unsafe {
                let page = base.add(first_page).cast::<c_void>();
                if mprotect(page, end - first_page, PROT_RW) != 0 {
                    return None;
                }
                std::ptr::copy_nonoverlapping(code.as_ptr(), base.add(start).cast::<u32>(), code.len());
                if mprotect(page, end - first_page, PROT_RX) != 0 {
                    return None;
                }
                flush(base.add(start) as usize, bytes);
            }
            self.used = (start + bytes + 15) & !15;
            Some(base as usize + start)
        }

        pub(crate) fn reset(&mut self) {
            for chunk in self.chunks.drain(..) {
                // SAFETY: our mapping; callers dropped every entry into it
                unsafe {
                    munmap(chunk.cast(), CHUNK);
                }
            }
            self.used = 0;
        }

        pub(crate) const CALL: unsafe fn(usize, *mut Ctx) -> u64 = call;
    }

    impl Drop for Store {
        fn drop(&mut self) {
            self.reset();
        }
    }

    unsafe fn call(entry: usize, ctx: *mut Ctx) -> u64 {
        // SAFETY: `entry` is published code following the `native` ABI
        unsafe {
            let f: unsafe extern "C" fn(*mut Ctx) -> u64 = std::mem::transmute(entry);
            f(ctx)
        }
    }

    unsafe fn flush(start: usize, bytes: usize) {
        use std::arch::asm;
        let ctr: usize;
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

#[cfg(all(
    test,
    not(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"), feature = "jit-arm64"))
))]
mod imp {
    use super::Ctx;

    /// Test store: code executed by the AArch64 simulator
    #[derive(Default)]
    pub(crate) struct Store {
        code: Vec<Box<[u32]>>,
    }

    impl Store {
        pub(crate) fn publish(&mut self, code: &[u32]) -> Option<usize> {
            if self.code.len() >= 4096 {
                return None;
            }
            let boxed: Box<[u32]> = code.into();
            let entry = boxed.as_ptr() as usize;
            self.code.push(boxed);
            Some(entry)
        }

        pub(crate) fn reset(&mut self) {
            self.code.clear();
        }

        pub(crate) const CALL: unsafe fn(usize, *mut Ctx) -> u64 = call;
    }

    unsafe fn call(entry: usize, ctx: *mut Ctx) -> u64 {
        let mut sim = super::super::a64_sim::Sim::new();
        // SAFETY: generated code only accesses the runner's live state; calls go to the
        // trampoline function pointer stored in the context
        unsafe {
            sim.call(entry as *const u32, ctx as u64, &mut |target, x0, x1| {
                let f: extern "C" fn(*mut Ctx, u64) -> u64 = std::mem::transmute(target as usize);
                f(x0 as *mut Ctx, x1)
            })
        }
    }
}

#[cfg(not(any(
    test,
    all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"), feature = "jit-arm64")
)))]
mod imp {
    use super::Ctx;

    #[derive(Default)]
    pub(crate) struct Store;

    impl Store {
        pub(crate) fn publish(&mut self, _: &[u32]) -> Option<usize> {
            None
        }

        pub(crate) fn reset(&mut self) {}

        pub(crate) const CALL: unsafe fn(usize, *mut Ctx) -> u64 = call;
    }

    unsafe fn call(_: usize, _: *mut Ctx) -> u64 {
        unreachable!("no native code store in this build")
    }
}

pub(crate) use imp::Store;
