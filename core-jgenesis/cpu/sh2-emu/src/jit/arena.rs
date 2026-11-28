//! Storage for compiled blocks.
//!
//! On aarch64 Linux/Android (feature `jit-arm64`): anonymous mappings in 1 MB chunks, written
//! while RW and published RX (never RWX) with the architectural cache maintenance sequence. On
//! other hosts in tests: the code words are kept in memory and executed by the AArch64 simulator.
//! A store is reset wholesale when full; blocks drop their entries first.

use super::a64::Asm;
use super::form::Block;
use super::native::{Ctx, JitRet, Stubs};

/// Whether this build can run compiled blocks
pub(crate) const AVAILABLE: bool = cfg!(any(
    test,
    all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"), feature = "jit-arm64")
));

#[cfg(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"), feature = "jit-arm64"))]
mod imp {
    use super::{Asm, Block, Ctx, JitRet, Stubs};
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
        hot_used: usize,
        cold_top: usize,
        stubs: Stubs,
    }

    // SAFETY: the mappings are owned exclusively by the store and only touched through `&mut`
    unsafe impl Send for Store {}
    unsafe impl Sync for Store {}

    fn publish_range(base: *mut u8, offset: usize, words: &[u32]) -> bool {
        if words.is_empty() {
            return true;
        }
        let bytes = words.len() * 4;
        let first_page = offset & !(PAGE - 1);
        let end = (offset + bytes + PAGE - 1) & !(PAGE - 1);
        // SAFETY: `[offset, offset + bytes)` lies within the `CHUNK` mapping and is only
        // published while no compiled code in this store is running.
        unsafe {
            let page = base.add(first_page).cast::<c_void>();
            if mprotect(page, end - first_page, PROT_RW) != 0 {
                return false;
            }
            std::ptr::copy_nonoverlapping(words.as_ptr(), base.add(offset).cast::<u32>(), words.len());
            if mprotect(page, end - first_page, PROT_RX) != 0 {
                return false;
            }
            flush(base.add(offset) as usize, bytes);
        }
        true
    }

    impl Store {
        fn alloc_chunk(&mut self) -> Option<*mut u8> {
            if self.chunks.len() == MAX_CHUNKS {
                return None;
            }
            // MAP_PRIVATE | MAP_ANONYMOUS, mapped RX until written
            // SAFETY: anonymous mapping, the kernel picks the address
            let raw = unsafe { mmap(std::ptr::null_mut(), CHUNK, PROT_RX, 0x22, -1, 0) };
            if raw as isize == -1 || raw.is_null() {
                return None;
            }
            let base: *mut u8 = raw.cast();
            let (stub_code, stubs) = super::super::native::build_stubs(base as usize);
            let bytes = stub_code.len() * 4;
            let end = (bytes + PAGE - 1) & !(PAGE - 1);
            if end > CHUNK || !publish_range(base, 0, &stub_code) {
                unsafe { munmap(base.cast(), CHUNK) };
                return None;
            }
            self.chunks.push(base);
            self.hot_used = (bytes + 15) & !15;
            self.cold_top = CHUNK;
            self.stubs = stubs;
            Some(base)
        }

        pub(crate) fn publish_asm(
            &mut self,
            mut build: impl FnMut(&Stubs) -> Option<Asm>,
        ) -> Option<Option<usize>> {
            if self.chunks.is_empty() {
                self.alloc_chunk()?;
            }
            let mut base = *self.chunks.last()?;
            let mut asm = match build(&self.stubs) {
                Some(asm) => asm,
                None => return Some(None),
            };
            let mut hot_bytes = asm.hot_len() * 4;
            let mut cold_bytes = asm.cold_len() * 4;
            if self.hot_used + hot_bytes + cold_bytes > self.cold_top {
                base = self.alloc_chunk()?;
                asm = match build(&self.stubs) {
                    Some(asm) => asm,
                    None => return Some(None),
                };
                hot_bytes = asm.hot_len() * 4;
                cold_bytes = asm.cold_len() * 4;
                if self.hot_used + hot_bytes + cold_bytes > self.cold_top {
                    return Some(None);
                }
            }
            let hot_start = self.hot_used;
            let cold_start = self.cold_top - cold_bytes;
            let (hot_code, cold_code) =
                asm.finish_split(base as usize + hot_start, base as usize + cold_start);
            if !publish_range(base, hot_start, &hot_code)
                || !publish_range(base, cold_start, &cold_code)
            {
                return Some(None);
            }
            self.hot_used = hot_start + hot_bytes;
            self.cold_top = cold_start;
            Some(Some(base as usize + hot_start))
        }

        pub(crate) fn reset(&mut self) {
            for chunk in self.chunks.drain(..) {
                // SAFETY: our mapping; callers dropped every entry into it
                unsafe {
                    munmap(chunk.cast(), CHUNK);
                }
            }
            self.hot_used = 0;
            self.cold_top = 0;
            self.stubs = Stubs::default();
        }

        pub(crate) fn enter(&self) -> usize {
            self.stubs.enter
        }

        #[allow(dead_code)]
        pub(crate) const CALL: unsafe fn(usize, *mut Ctx, usize, u64, u64, *const Block, u64) -> JitRet = call;
    }

    impl Drop for Store {
        fn drop(&mut self) {
            self.reset();
        }
    }

    unsafe fn call(
        entry: usize,
        ctx: *mut Ctx,
        target_entry: usize,
        counter: u64,
        limit: u64,
        block: *const Block,
        ticks: u64,
    ) -> JitRet {
        // SAFETY: `entry` is published code following the `native` ABI
        unsafe {
            let f: unsafe extern "C" fn(*mut Ctx, usize, u64, u64, *const Block, u64) -> JitRet =
                std::mem::transmute(entry);
            f(ctx, target_entry, counter, limit, block, ticks)
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
    use super::{Asm, Block, Ctx, JitRet, Stubs};

    const CHUNK_WORDS: usize = 1 << 18;
    const MAX_CHUNKS: usize = 16;

    /// Test store: contiguous 1 MB chunks executed by the AArch64 simulator (so BL stays in range)
    #[derive(Default)]
    pub(crate) struct Store {
        chunks: Vec<Box<[u32]>>,
        hot_used_words: usize,
        cold_top_words: usize,
        stubs: Stubs,
    }

    impl Store {
        fn alloc_chunk(&mut self) -> Option<usize> {
            if self.chunks.len() >= MAX_CHUNKS {
                return None;
            }
            let mut chunk = vec![0u32; CHUNK_WORDS].into_boxed_slice();
            let base = chunk.as_ptr() as usize;
            let (stub_code, stubs) = super::super::native::build_stubs(base);
            if stub_code.len() > CHUNK_WORDS {
                return None;
            }
            chunk[..stub_code.len()].copy_from_slice(&stub_code);
            self.chunks.push(chunk);
            self.hot_used_words = (stub_code.len() + 3) & !3;
            self.cold_top_words = CHUNK_WORDS;
            self.stubs = stubs;
            Some(base)
        }

        pub(crate) fn publish_asm(
            &mut self,
            mut build: impl FnMut(&Stubs) -> Option<Asm>,
        ) -> Option<Option<usize>> {
            if self.chunks.is_empty() {
                self.alloc_chunk()?;
            }
            let mut base = self.chunks.last()?.as_ptr() as usize;
            let mut asm = match build(&self.stubs) {
                Some(asm) => asm,
                None => return Some(None),
            };
            if self.hot_used_words + asm.hot_len() + asm.cold_len() > self.cold_top_words {
                base = self.alloc_chunk()?;
                asm = match build(&self.stubs) {
                    Some(asm) => asm,
                    None => return Some(None),
                };
                if self.hot_used_words + asm.hot_len() + asm.cold_len() > self.cold_top_words {
                    return Some(None);
                }
            }
            let hot_start = self.hot_used_words;
            let cold_start = self.cold_top_words - asm.cold_len();
            let (hot_code, cold_code) = asm.finish_split(base + hot_start * 4, base + cold_start * 4);
            let chunk = self.chunks.last_mut()?;
            chunk[hot_start..hot_start + hot_code.len()].copy_from_slice(&hot_code);
            if !cold_code.is_empty() {
                chunk[cold_start..cold_start + cold_code.len()].copy_from_slice(&cold_code);
            }
            self.hot_used_words = hot_start + hot_code.len();
            self.cold_top_words = cold_start;
            Some(Some(base + hot_start * 4))
        }

        pub(crate) fn reset(&mut self) {
            self.chunks.clear();
            self.hot_used_words = 0;
            self.cold_top_words = 0;
            self.stubs = Stubs::default();
        }

        pub(crate) fn enter(&self) -> usize {
            self.stubs.enter
        }

        #[allow(dead_code)]
        pub(crate) const CALL: unsafe fn(usize, *mut Ctx, usize, u64, u64, *const Block, u64) -> JitRet = call;
    }

    unsafe fn call(
        entry: usize,
        ctx: *mut Ctx,
        target_entry: usize,
        counter: u64,
        limit: u64,
        block: *const Block,
        ticks: u64,
    ) -> JitRet {
        let mut sim = super::super::a64_sim::Sim::new();
        // SAFETY: generated code only accesses the runner's live state; calls go to the
        // trampoline function pointer stored in the context
        let (packed, counter) = unsafe {
            sim.call6(
                entry as *const u32,
                [ctx as u64, target_entry as u64, counter, limit, block as u64, ticks],
                &mut |target, x0, x1| {
                    let f: extern "C" fn(*mut Ctx, u64) -> u64 = std::mem::transmute(target as usize);
                    f(x0 as *mut Ctx, x1)
                },
            )
        };
        JitRet { packed, counter }
    }
}

#[cfg(not(any(
    test,
    all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"), feature = "jit-arm64")
)))]
mod imp {
    use super::{Asm, Block, Ctx, JitRet, Stubs};

    #[derive(Default)]
    pub(crate) struct Store;

    impl Store {
        pub(crate) fn publish_asm(&mut self, _: impl FnMut(&Stubs) -> Option<Asm>) -> Option<Option<usize>> {
            None
        }

        pub(crate) fn reset(&mut self) {}

        pub(crate) fn enter(&self) -> usize {
            0
        }

        #[allow(dead_code)]
        pub(crate) const CALL: unsafe fn(usize, *mut Ctx, usize, u64, u64, *const Block, u64) -> JitRet = call;
    }

    unsafe fn call(_: usize, _: *mut Ctx, _: usize, _: u64, _: u64, _: *const Block, _: u64) -> JitRet {
        unreachable!("no native code store in this build")
    }
}

pub(crate) use imp::Store;

/// Thread-local shared compiled-code pool so Master and Slave SH-2 on the same emulation thread
/// share ONE `Store`, ONE stub table, and ONE content-addressed compiled-code cache.
#[cfg(any(test, feature = "jit-arm64"))]
pub(crate) mod pool {
    use super::imp::Store;
    use super::{Asm, Stubs};
    use super::super::Uop;
    use std::cell::{Cell, RefCell};
    use std::collections::HashMap;
    use std::sync::atomic::{AtomicU64, Ordering};

    const MAX_CACHE_ENTRIES: usize = 8192;

    #[derive(Clone, Copy, Debug, Default)]
    pub(crate) struct PoolMeta {
        pub(crate) id: u64,
        pub(crate) epoch: u64,
        pub(crate) enter: usize,
    }

    #[derive(Default)]
    struct SharedPool {
        store: Store,
        code_cache: HashMap<(u32, u64), (Box<[u16]>, usize)>,
    }

    static NEXT_POOL_ID: AtomicU64 = AtomicU64::new(1);

    thread_local! {
        static POOL_ID: Cell<u64> = Cell::new(NEXT_POOL_ID.fetch_add(1, Ordering::Relaxed));
        static POOL_GEN: Cell<u64> = const { Cell::new(1) };
        static POOL_ENTER: Cell<usize> = const { Cell::new(0) };
        static POOL: RefCell<SharedPool> = RefCell::new(SharedPool::default());
    }

    #[inline(always)]
    pub(crate) fn pool_meta() -> PoolMeta {
        PoolMeta {
            id: POOL_ID.get(),
            epoch: POOL_GEN.get(),
            enter: POOL_ENTER.get(),
        }
    }

    #[inline(always)]
    fn cache_hit(cache: &HashMap<(u32, u64), (Box<[u16]>, usize)>, pc: u32, hash: u64, ops: &[Uop]) -> Option<usize> {
        let (saved_ops, entry) = cache.get(&(pc, hash))?;
        (saved_ops.len() == ops.len()
            && saved_ops.iter().zip(ops.iter()).all(|(&w, op)| w == op.opcode))
        .then_some(*entry)
    }

    pub(crate) fn pool_lookup(pc: u32, hash: u64, ops: &[Uop]) -> Option<(usize, PoolMeta)> {
        let meta = pool_meta();
        let entry = POOL.with_borrow(|p| cache_hit(&p.code_cache, pc, hash, ops))?;
        Some((entry, meta))
    }

    pub(crate) fn pool_compile_or_get(
        pc: u32,
        hash: u64,
        ops: &[Uop],
        mut build: impl FnMut(&Stubs) -> Option<Asm>,
    ) -> (Option<usize>, PoolMeta, bool) {
        POOL.with_borrow_mut(|p| {
            if let Some(entry) = cache_hit(&p.code_cache, pc, hash, ops) {
                if POOL_ENTER.get() == 0 {
                    POOL_ENTER.set(p.store.enter());
                }
                return (Some(entry), pool_meta(), false);
            }

            let published = p.store.publish_asm(&mut build);
            let (entry, reset_happened) = match published {
                Some(e) => (e, false),
                None => {
                    p.store.reset();
                    p.code_cache.clear();
                    POOL_GEN.set(POOL_GEN.get().wrapping_add(1));
                    POOL_ENTER.set(0);
                    (p.store.publish_asm(&mut build).flatten(), true)
                }
            };
            let _ = reset_happened;
            if let Some(e) = entry {
                POOL_ENTER.set(p.store.enter());
                if p.code_cache.len() < MAX_CACHE_ENTRIES {
                    let opcodes: Box<[u16]> = ops.iter().map(|o| o.opcode).collect();
                    p.code_cache.insert((pc, hash), (opcodes, e));
                }
                (Some(e), pool_meta(), true)
            } else {
                (None, pool_meta(), false)
            }
        })
    }
}
