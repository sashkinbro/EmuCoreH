//! W^X ARM64 code ownership shared by experimental CPU translators.
//! Only available on little-endian ARM64 Linux/Android.
use std::ffi::c_void;
use std::ptr::NonNull;
use std::rc::Rc;

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
    mapping: Rc<Mapping>,
    offset: usize,
}

#[derive(Debug)]
struct Mapping {
    ptr: NonNull<c_void>,
    len: usize,
}

impl PublishedCode {
    /// Page-rounded bytes owned by this mapping. Entries published together
    /// share this extent, so a cache must charge their batch only once.
    pub fn mapping_len(&self) -> usize {
        self.mapping.len
    }

    pub fn publish(code: &[u32]) -> Option<Self> {
        Self::publish_batch(&[code])?.pop()
    }

    /// Pack independent instruction bodies into one immutable RX mapping.
    ///
    /// Each body starts on a 16-byte boundary and owns a reference to the shared
    /// mapping, so evicting one body cannot invalidate another. Publication uses
    /// one mmap/mprotect/cache flush per batch and never changes an existing RX
    /// page back to writable. Bodies must not rely on adjacency or fall through
    /// into the next body. The caller still validates every entry's instruction ABI.
    pub fn publish_batch(bodies: &[&[u32]]) -> Option<Vec<Self>> {
        if bodies.is_empty() {
            return None;
        }
        let mut offsets = Vec::with_capacity(bodies.len());
        let mut bytes = 0usize;
        for body in bodies {
            if body.is_empty() {
                return None;
            }
            bytes = bytes.checked_add(15)? & !15;
            offsets.push(bytes);
            bytes = bytes.checked_add(body.len().checked_mul(4)?)?;
        }
        // SAFETY: no arguments and no preconditions for getpagesize.
        let page = usize::try_from(unsafe { getpagesize() }).ok()?;
        if !page.is_power_of_two() {
            return None;
        }
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
        let mapping = Rc::new(Mapping { ptr, len });
        for (body, &offset) in bodies.iter().zip(&offsets) {
            // SAFETY: checked byte sizes/aligned offsets above fit in the live RW
            // mapping, and the source instruction vectors cannot alias this new map.
            unsafe {
                std::ptr::copy_nonoverlapping(
                    body.as_ptr(),
                    raw.cast::<u8>().add(offset).cast::<u32>(),
                    body.len(),
                );
            }
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
        Some(
            offsets
                .into_iter()
                .map(|offset| Self { mapping: Rc::clone(&mapping), offset })
                .collect(),
        )
    }

    /// Entry address remains valid only while this owner lives.
    ///
    /// # Safety
    /// The caller must validate the generated instructions and use their exact ABI,
    /// with valid borrowed storage for every generated memory access.
    pub unsafe fn entry_pointer(&self) -> *const c_void {
        // SAFETY: offset was checked against the mapping extent on publication.
        unsafe { self.mapping.ptr.as_ptr().cast::<u8>().add(self.offset).cast::<c_void>() }
    }
}

impl Drop for Mapping {
    fn drop(&mut self) {
        // SAFETY: the last entry owns the mapping exclusively; no generated call
        // can outlive its borrowed PublishedCode, which retains a mapping reference.
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn packed_entries_share_rx_pages_and_outlive_other_entries() {
        let first = [0x52800540, 0xD65F03C0]; // mov w0,#42; ret
        let second = [0x528000E0, 0xD65F03C0]; // mov w0,#7; ret
        let mut entries = PublishedCode::publish_batch(&[&first, &second]).unwrap();
        assert_eq!(entries.len(), 2);
        // Each entry is aligned, but tiny bodies must not consume separate pages.
        let a = unsafe { entries[0].entry_pointer() } as usize;
        let b = unsafe { entries[1].entry_pointer() } as usize;
        assert_eq!(a % 16, 0);
        assert_eq!(b - a, 16);
        let invoke = |entry: &PublishedCode| unsafe {
            let body: unsafe extern "C" fn() -> u32 = std::mem::transmute(entry.entry_pointer());
            body()
        };
        assert_eq!(invoke(&entries[0]), 42);
        assert_eq!(invoke(&entries[1]), 7);
        entries.remove(0);
        assert_eq!(invoke(&entries[0]), 7, "shared mapping must remain alive");
        let maps = std::fs::read_to_string("/proc/self/maps").unwrap();
        let permissions = maps
            .lines()
            .find_map(|line| {
                let mut fields = line.split_whitespace();
                let mut range = fields.next()?.split('-');
                let start = usize::from_str_radix(range.next()?, 16).ok()?;
                let end = usize::from_str_radix(range.next()?, 16).ok()?;
                (b >= start && b < end).then(|| fields.next().unwrap().to_owned())
            })
            .unwrap();
        assert!(permissions.starts_with("r-x"), "published pages must be RX: {permissions}");
    }

    #[test]
    fn packed_entries_reject_empty_bodies_and_support_page_boundaries() {
        assert!(PublishedCode::publish_batch(&[]).is_none());
        assert!(PublishedCode::publish_batch(&[&[]]).is_none());
        assert!(PublishedCode::publish_batch(&[&[0xD65F03C0], &[]]).is_none());
        let page = usize::try_from(unsafe { getpagesize() }).unwrap();
        let mut first = vec![0xD503201F; page / 4]; // NOPs, crossing a runtime page boundary
        first.extend([0x52800540, 0xD65F03C0]);
        let mut entries =
            PublishedCode::publish_batch(&[&first, &[0x528000E0, 0xD65F03C0]]).unwrap();
        let second = entries.pop().unwrap();
        drop(entries);
        let invoke: unsafe extern "C" fn() -> u32 =
            unsafe { std::mem::transmute(second.entry_pointer()) };
        assert_eq!(unsafe { invoke() }, 7);
    }
}
