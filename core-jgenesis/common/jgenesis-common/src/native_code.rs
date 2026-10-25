//! W^X ARM64 code ownership shared by experimental CPU translators.
//! Only available on little-endian ARM64 Linux/Android.
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

#[derive(Debug)]
pub struct PublishedCode {
    ptr: NonNull<c_void>,
    len: usize,
}

impl PublishedCode {
    pub fn publish(code: &[u32]) -> Option<Self> {
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
        // SAFETY: cache maintenance spans this live RX mapping. Executing callers
        // independently validate their instruction stream and ABI.
        unsafe {
            flush(raw as usize, bytes);
        }
        Some(body)
    }

    /// Entry address remains valid only while this owner lives.
    ///
    /// # Safety
    /// The caller must validate the generated instructions and use their exact ABI,
    /// with valid borrowed storage for every generated memory access.
    pub unsafe fn entry_pointer(&self) -> *const c_void {
        self.ptr.as_ptr()
    }
}

impl Drop for PublishedCode {
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
