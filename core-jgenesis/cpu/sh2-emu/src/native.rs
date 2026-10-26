//! Experimental ARM64 instruction bodies and guarded, execution-learned blocks.

#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
fn emit(opcode: u16) -> Option<Vec<u32>> {
    if let Some(code) = complex::emit(opcode) {
        return Some(code);
    }
    if let Some(code) = special::emit(opcode) {
        return Some(code);
    }
    if let Some(code) = branch::emit(opcode) {
        return Some(code);
    }
    if let Some(code) = memory::emit(opcode) {
        return Some(code);
    }
    let mut body = emit_body(opcode)?;
    let mut code = vec![0xF9400405, 0xF9400000]; // Context.t -> x5; Context.gpr -> x0
    code.append(&mut body);
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
fn emit_body(opcode: u16) -> Option<Vec<u32>> {
    if let Some(code) = register_alu::emit(opcode) {
        return Some(code);
    }
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

#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
fn supported(opcode: u16) -> bool {
    static SUPPORTED: std::sync::LazyLock<Box<[bool; 65536]>> = std::sync::LazyLock::new(|| {
        Box::new(std::array::from_fn(|opcode| emit(opcode as u16).is_some()))
    });
    SUPPORTED[usize::from(opcode)]
}

#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
fn observed_trace_is_covered(previous: &[u16], observed: &[(u32, u16)]) -> bool {
    previous.len() >= observed.len()
        && previous.iter().zip(observed).all(|(&old, &(_, opcode))| old == opcode)
}

/// Host execution context. Keep separate from `Sh2`: clone and savestate operations never
/// copy executable pointers. Unsupported hosts and allocation failures use the interpreter.
/// Blocks are learned from real executed fetches, never speculative guest-memory reads.
#[derive(Default)]
pub struct NativeExecutor {
    #[cfg(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    ))]
    banks: Vec<Option<std::rc::Rc<CodeBank>>>,
    #[cfg(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    ))]
    cache_clock: u64,
    #[cfg(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    ))]
    block_metadata: rustc_hash::FxHashMap<u32, (u64, Vec<u16>, usize)>,
    #[cfg(all(target_arch = "aarch64", target_endian = "little", any(target_os = "android", target_os = "linux")))]
    block_clock: std::collections::VecDeque<(u32, u64)>,
    #[cfg(all(target_arch = "aarch64", target_endian = "little", any(target_os = "android", target_os = "linux")))]
    block_bytes: usize,
    #[cfg(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    ))]
    blocks: rustc_hash::FxHashMap<u32, jgenesis_common::native_code::PublishedCode>,
    #[cfg(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    ))]
    trace: Vec<(u32, u16)>,
    #[cfg(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    ))]
    fast_blocks: rustc_hash::FxHashMap<u32, fast::Compiled>,
    #[cfg(all(
        test,
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    ))]
    block_publications: u64,
    fast_instructions: u64,
    native_blocks: u64,
    block_instructions: u64,
    budget_exits: u64,
    stop_exits: u64,
    interpreted_instructions: u64,
    guard_exits: u64,
    blocks_disabled: bool,
    disabled: bool,
    native_instructions: u64,
}

impl NativeExecutor {
    /// Count of instructions actually executed in generated ARM64 code, not cache hits or attempts.
    #[must_use]
    pub fn native_instructions(&self) -> u64 {
        self.native_instructions
    }

    /// Instructions executed without per-instruction prepare/completion callbacks.
    #[must_use]
    pub fn fast_instructions(&self) -> u64 {
        self.fast_instructions
    }

    /// Entries into generated multi-instruction code (including guarded exits).
    #[must_use]
    pub fn native_blocks(&self) -> u64 {
        self.native_blocks
    }

    /// Native ALU bodies executed inside multi-instruction code.
    #[must_use]
    pub fn block_instructions(&self) -> u64 {
        self.block_instructions
    }

    #[must_use]
    pub fn budget_exits(&self) -> u64 {
        self.budget_exits
    }

    #[must_use]
    pub fn stop_exits(&self) -> u64 {
        self.stop_exits
    }

    /// Instructions dispatched to the interpreter, including fetched guard mismatches.
    #[must_use]
    pub fn interpreted_instructions(&self) -> u64 {
        self.interpreted_instructions
    }

    /// Blocks whose next PC or fetched opcode no longer matched their learned trace.
    #[must_use]
    pub fn guard_exits(&self) -> u64 {
        self.guard_exits
    }

    pub(crate) fn finish_trace(&mut self) {
        #[cfg(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        ))]
        {
            if self.trace.is_empty() {
                return;
            }
            self.publish_trace_if_needed();
            // Keep the execution-learned trace allocation across every exit path,
            // including compatible prefixes, short traces and publication failures.
            self.trace.clear();
        }
    }

    #[cfg(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    ))]
    fn publish_trace_if_needed(&mut self) {
        if self.disabled {
            return;
        }
        let entry = self.trace[0].0;
        self.cache_clock = self.cache_clock.wrapping_add(1);
        if let Some((age, previous, _)) = self.block_metadata.get_mut(&entry) {
            *age = self.cache_clock;
            // Traces are contiguous from this same entry PC. A shorter execution
            // slice does not invalidate a longer guarded block: its existing budget
            // and stop checks exit before another guest fetch or body can execute.
            if observed_trace_is_covered(previous, &self.trace) {
                return;
            }
        }
        if self.trace.len() < 2 {
            self.remove_block(entry);
            return;
        }
        let Some(code) = block::emit(&self.trace) else {
            self.remove_block(entry);
            return;
        };
        let fast_words = fast::emit(&self.trace);
        let refs = [code.as_slice(), fast_words.as_deref().unwrap_or(&[])];
        let count = if fast_words.is_some() { 2 } else { 1 };
        let Some(mut published) =
            jgenesis_common::native_code::PublishedCode::publish_batch(&refs[..count])
        else {
            self.disabled = true;
            self.remove_block(entry);
            #[cfg(feature = "jit-preview")]
            jgenesis_common::jit::record_cache_event(jgenesis_common::jit::CacheEvent::Failure);
            return;
        };
        #[cfg(test)]
        {
            self.block_publications += 1;
        }
        let body = published.remove(0);
        #[cfg(feature = "jit-preview")]
        jgenesis_common::jit::record_cache_event(jgenesis_common::jit::CacheEvent::Compile);
        let mapped_bytes = body.mapping_len();
        self.remove_block(entry);
        if mapped_bytes > 32 * 1024 * 1024 {
            return;
        }
        // CLOCK keeps a gameplay working set larger than 512 PCs without an
        // O(resident blocks) oldest-age scan on every publication. Each batch
        // contains ordinary/fast code in one shared, page-rounded mapping.
        while self.blocks.len() >= 2048 || self.block_bytes + mapped_bytes > 32 * 1024 * 1024 {
            let (victim, seen) = self.block_clock.pop_front().expect("resident clock entry");
            let &(age, _, bytes) = &self.block_metadata[&victim];
            if age != seen {
                self.block_clock.push_back((victim, age));
                continue;
            }
            self.blocks.remove(&victim);
            self.fast_blocks.remove(&victim);
            self.block_metadata.remove(&victim);
            self.block_bytes -= bytes;
            #[cfg(feature = "jit-preview")]
            jgenesis_common::jit::record_cache_event(jgenesis_common::jit::CacheEvent::Evict);
        }
        let opcodes = self.trace.iter().map(|&(_, op)| op).collect();
        self.block_metadata.insert(entry, (self.cache_clock, opcodes, mapped_bytes));
        self.block_clock.push_back((entry, self.cache_clock));
        self.block_bytes += mapped_bytes;
        self.blocks.insert(entry, body);
        // A replacement may no longer qualify for direct/fast fetches. Never leave
        // its previous fast variant paired with the newly learned ordinary block.
        self.fast_blocks.remove(&entry);
        if let Some(code) = published.pop() {
            self.fast_blocks.insert(
                entry,
                fast::Compiled {
                    code,
                    #[cfg(any(test, feature = "jit-preview"))]
                    opcodes: self.trace.iter().map(|&(_, op)| op).collect(),
                },
            );
        }
    }

    #[cfg(all(target_arch = "aarch64", target_endian = "little", any(target_os = "android", target_os = "linux")))]
    pub(super) fn remove_block(&mut self, entry: u32) {
        // Metadata and code are inserted/removed together. Single-instruction
        // slices often have no block: avoid hashing two more empty lookups.
        let Some((_, _, bytes)) = self.block_metadata.remove(&entry) else {
            return;
        };
        self.blocks.remove(&entry);
        self.fast_blocks.remove(&entry);
        self.block_bytes -= bytes;
        self.block_clock.retain(|&(pc, _)| pc != entry);
    }

    /// Keep single-instruction translation while disabling learned blocks, for A/B measurement.
    pub fn disable_blocks(&mut self) {
        self.blocks_disabled = true;
        #[cfg(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        ))]
        {
            self.blocks.clear();
            self.fast_blocks.clear();
            self.block_metadata.clear();
            self.block_clock.clear();
            self.block_bytes = 0;
            self.trace.clear();
        }
    }

    /// Disable translation (also useful when executable memory is forbidden by the host).
    pub fn disable(&mut self) {
        self.disabled = true;
    }

    pub(crate) fn execute<Bus: crate::bus::BusInterface>(
        &mut self,
        cpu: &mut crate::Sh2,
        bus: &mut Bus,
        opcode: u16,
    ) -> bool {
        if self.disabled {
            return false;
        }
        #[cfg(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        ))]
        {
            let high = usize::from(opcode >> 8);
            if self.banks.is_empty() {
                self.banks.resize_with(256, || None);
            }
            if self.banks[high].is_none() {
                if !supported(opcode) {
                    return false;
                }
                let Some(bank) = code_bank(opcode) else {
                    self.disabled = true;
                    #[cfg(feature = "jit-preview")]
                    jgenesis_common::jit::record_cache_event(
                        jgenesis_common::jit::CacheEvent::Failure,
                    );
                    return false;
                };
                self.banks[high] = Some(bank);
            } else {
                #[cfg(feature = "jit-preview")]
                jgenesis_common::jit::record_cache_event(jgenesis_common::jit::CacheEvent::Hit);
            }
            let Some(body) =
                self.banks[high].as_ref().unwrap().entries[usize::from(opcode as u8)].as_ref()
            else {
                return false;
            };
            // SAFETY: x0 points to the stack-owned Context with typed callbacks and valid
            // register/flag pointers. Bodies preserve AAPCS64 callee-saved registers
            // and call data-access helpers only while CPU and bus are exclusively borrowed.
            unsafe {
                let run: unsafe extern "C" fn(*mut Context) =
                    std::mem::transmute(body.entry_pointer());
                let mut runtime = memory::Runtime { cpu, bus, fast: std::ptr::null() };
                let mut context = memory::context(&mut runtime);
                run(&mut context);
            }
            #[cfg(any(test, feature = "jit-preview"))]
            {
                self.native_instructions += 1;
            }
            #[cfg(feature = "jit-preview")]
            jgenesis_common::jit::record_dispatch(jgenesis_common::jit::DispatchKind::Leaf, 1);
            true
        }
        #[cfg(not(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        )))]
        {
            let _ = (cpu, bus, opcode);
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compiled_longer_trace_covers_every_observed_prefix() {
        let previous = [0xE101, 0x7101, 0x6213, 0x312C];
        let observed: Vec<_> =
            previous.iter().enumerate().map(|(i, &op)| (0x100 + 2 * i as u32, op)).collect();
        for length in 1..=previous.len() {
            assert!(
                observed_trace_is_covered(&previous, &observed[..length]),
                "compatible prefix length {length} must reuse existing code"
            );
        }
        let mut changed = observed.clone();
        changed[1].1 = 0x7102;
        assert!(!observed_trace_is_covered(&previous, &changed[..2]));
        let mut longer = observed;
        longer.push((0x108, 0x0009));
        assert!(!observed_trace_is_covered(&previous, &longer));
    }

    #[cfg(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    ))]
    #[test]
    fn variable_trace_flush_lengths_reuse_publication_and_trace_capacity() {
        let mut native = NativeExecutor::default();
        native.trace.reserve(16);
        let capacity = native.trace.capacity();
        let trace: Vec<_> = [0xE101, 0x7101, 0x6213, 0x312C, 0x212A, 0x0009, 0x7102, 0x7103]
            .into_iter()
            .enumerate()
            .map(|(i, op)| (0x100 + 2 * i as u32, op))
            .collect();
        native.trace.extend_from_slice(&trace);
        native.finish_trace();
        assert_eq!(native.block_publications, 1);
        assert_eq!(native.trace.capacity(), capacity);
        let entry = unsafe { native.blocks[&0x100].entry_pointer() };
        let fast = unsafe { native.fast_blocks[&0x100].code.entry_pointer() };
        for _ in 0..16 {
            for length in [1, 2, 7, 3, 8, 4, 6, 5] {
                native.trace.extend_from_slice(&trace[..length]);
                native.finish_trace();
                assert!(native.trace.is_empty());
                assert_eq!(native.trace.capacity(), capacity);
                assert_eq!(
                    native.block_publications, 1,
                    "prefix length {length} must not republish"
                );
                assert_eq!(unsafe { native.blocks[&0x100].entry_pointer() }, entry);
                assert_eq!(unsafe { native.fast_blocks[&0x100].code.entry_pointer() }, fast);
                assert_eq!(native.block_metadata[&0x100].1.len(), trace.len());
            }
        }
        native.trace.extend_from_slice(&trace);
        native.trace.push((0x110, 0x0009));
        native.finish_trace();
        assert_eq!(native.block_publications, 2);
        assert_eq!(native.block_metadata[&0x100].1.len(), 9);
        assert_eq!(native.trace.capacity(), capacity);
        // One changed observed opcode is enough to invalidate both variants even
        // when the new execution slice is too short to compile a replacement.
        native.trace.push((0x100, 0xE17F));
        native.finish_trace();
        assert!(!native.blocks.contains_key(&0x100));
        assert!(!native.fast_blocks.contains_key(&0x100));
        assert!(!native.block_metadata.contains_key(&0x100));
        assert_eq!(native.trace.capacity(), capacity);
        native.disabled = true;
        native.trace.extend_from_slice(&trace);
        native.finish_trace();
        assert!(native.trace.is_empty());
        assert_eq!(native.trace.capacity(), capacity);
    }

    #[cfg(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    ))]
    #[test]
    fn replacing_or_rejecting_trace_removes_an_obsolete_fast_variant() {
        let mut native = NativeExecutor::default();
        let prefix = [(0x3FFFFFFC, 0x0009), (0x3FFFFFFE, 0x0009)];
        native.trace.extend_from_slice(&prefix);
        native.finish_trace();
        assert!(native.fast_blocks.contains_key(&prefix[0].0));
        // This contiguous boundary extension is accepted by the ordinary emitter
        // but lies outside the fast emitter's supported fetch-address classes.
        native.trace.extend_from_slice(&prefix);
        native.trace.push((0x40000000, 0x0009));
        native.finish_trace();
        assert_eq!(native.block_publications, 2);
        assert!(native.blocks.contains_key(&prefix[0].0));
        assert!(!native.fast_blocks.contains_key(&prefix[0].0));
        native.trace.extend_from_slice(&[(0x100, 0x0009), (0x102, 0x0009)]);
        native.finish_trace();
        assert!(native.fast_blocks.contains_key(&0x100));
        native.trace.extend_from_slice(&[(0x100, 0xE101), (0x102, 0xFFFF)]);
        native.finish_trace();
        assert!(!native.blocks.contains_key(&0x100));
        assert!(!native.fast_blocks.contains_key(&0x100));
        assert!(!native.block_metadata.contains_key(&0x100));
    }

    #[cfg(all(target_arch = "aarch64", target_endian = "little", any(target_os = "android", target_os = "linux")))]
    #[test]
    fn learned_blocks_keep_a_combat_sized_working_set_without_republication() {
        let mut native = NativeExecutor::default();
        for _ in 0..3 {
            for index in 0..768 {
                let pc = 0x26000100 + index * 32;
                native.trace.extend_from_slice(&[(pc, 0x0009), (pc + 2, 0xE101)]);
                native.finish_trace();
            }
        }
        assert_eq!(native.block_publications, 768);
        assert_eq!(native.blocks.len(), 768);
    }

    #[cfg(all(target_arch = "aarch64", target_endian = "little", any(target_os = "android", target_os = "linux")))]
    #[test]
    fn learned_clock_bounds_page_rounded_storage_and_removes_invalidated_entries() {
        let mut native = NativeExecutor::default();
        for index in 0..2200 {
            let pc = 0x26000100 + index * 32;
            native.trace.extend((0..16).map(|i| (pc + 2 * i, 0x011F)));
            native.finish_trace();
            assert!(native.blocks.len() <= 2048);
            assert!(native.block_bytes <= 32 * 1024 * 1024);
            assert_eq!(native.block_clock.len(), native.blocks.len());
            assert_eq!(native.block_bytes, native.block_metadata.values().map(|v| v.2).sum());
        }
        let pc = 0x26000100 + 2199 * 32;
        let old_bytes = native.block_bytes;
        let entry_bytes = native.block_metadata[&pc].2;
        native.remove_block(pc);
        assert_eq!(native.block_bytes, old_bytes - entry_bytes);
        assert!(!native.fast_blocks.contains_key(&pc));
        assert!(!native.block_clock.iter().any(|v| v.0 == pc));
        native.disable_blocks();
        assert_eq!(native.block_bytes, 0);
        assert!(native.block_clock.is_empty());
    }

    #[test]
    fn emits_explicit_context_abi_and_rejects_reserved_operations() {
        // Context exposes GPR/T pointers; generated code never embeds Sh2 layout.
        assert_eq!(
            emit(0x6123),
            Some(vec![0xF9400405, 0xF9400000, 0xB9400801, 0xB9000401, 0xD65F03C0])
        );
        assert_eq!(emit_body(0x0009), Some(vec![0xD65F03C0]));
        for opcode in [0xFFFF] {
            assert_eq!(emit(opcode), None, "{opcode:04X}");
        }
    }
}

#[cfg(test)]
mod differential;

#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
mod block;

impl<Bus: crate::bus::BusInterface> crate::ExecutionBody<Bus> for NativeExecutor {
    fn opcode(&mut self, cpu: &mut crate::Sh2, _bus: &mut Bus, opcode: u16) -> bool {
        let pc = match cpu.data_ctx {
            crate::bus::AccessContext::Data { pc, .. } => pc,
            _ => unreachable!(),
        };
        #[cfg(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        ))]
        if !self.disabled && !self.blocks_disabled {
            if self.trace.last().is_some_and(|&(previous, _)| previous.wrapping_add(2) != pc)
                || !supported(opcode)
            {
                self.finish_trace();
            }
            if supported(opcode) {
                self.trace.push((pc, opcode));
                if self.trace.len() == 16 {
                    self.finish_trace();
                }
            }
        }
        let _ = pc;
        let executed = self.execute(cpu, _bus, opcode);
        #[cfg(any(test, feature = "jit-preview"))]
        {
            self.interpreted_instructions += u64::from(!executed);
        }
        executed
    }

    fn block(&mut self, cpu: &mut crate::Sh2, bus: &mut Bus, ticks: u64) -> (u64, bool) {
        #[cfg(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        ))]
        if !self.disabled && !self.blocks_disabled {
            if let Some(result) = fast::run(self, cpu, bus, ticks) {
                if result.0 != 0 || result.1 {
                    return result;
                }
                // A missing/disabled instruction-cache line exits before any
                // guest fetch. The ordinary block still preserves that fetch's
                // timing and cache effects; do not degrade to per-op wrappers.
            }
            return block::run(self, cpu, bus, ticks);
        }
        let _ = (cpu, bus, ticks);
        (0, false)
    }
}

#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
#[repr(C)]
struct Context {
    gpr: *mut u32,
    t: *mut bool,
    runtime: *mut std::ffi::c_void,
    read: unsafe extern "C" fn(*mut std::ffi::c_void, u32, u32) -> u32,
    write: unsafe extern "C" fn(*mut std::ffi::c_void, u32, u32, u32),
    next_pc: *mut u32,
    gbr: *mut u32,
    pc: *mut u32,
    delay: *mut bool,
    pr: *mut u32,
    cycles: unsafe extern "C" fn(*mut std::ffi::c_void, u32),
    mach: *mut u32,
    macl: *mut u32,
    m: *mut bool,
    q: *mut bool,
    s: *mut bool,
    mask: *mut u8,
    vbr: *mut u32,
}
#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
mod register_alu;

#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
mod memory;

#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
mod branch;

#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
mod special;

#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
mod complex;

#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
mod fast;

#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
struct CodeBank {
    entries: Box<[Option<jgenesis_common::native_code::PublishedCode>; 256]>,
}
#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
fn code_bank(opcode: u16) -> Option<std::rc::Rc<CodeBank>> {
    // Bodies are immutable and CPU/bus-independent: their only external bindings
    // are the live Context pointers/callbacks. One thread shares each bank between
    // all SH2 executors. At most 256 banks/65536 bodies can ever be retained.
    thread_local! {static BANKS:std::cell::RefCell<[Option<std::rc::Rc<CodeBank>>;256]>=
    std::cell::RefCell::new(std::array::from_fn(|_|None));}
    BANKS.with(|banks| {
        let high = usize::from(opcode >> 8);
        if let Some(bank) = &banks.borrow()[high] {
            #[cfg(feature = "jit-preview")]
            jgenesis_common::jit::record_cache_event(jgenesis_common::jit::CacheEvent::Hit);
            return Some(bank.clone());
        }
        let mut bodies = Vec::new();
        let mut indices = Vec::new();
        for low in 0..256 {
            if let Some(body) = emit(((high as u16) << 8) | low) {
                indices.push(usize::from(low));
                bodies.push(body);
            }
        }
        let refs: Vec<_> = bodies.iter().map(Vec::as_slice).collect();
        let published = jgenesis_common::native_code::PublishedCode::publish_batch(&refs)?;
        let mut entries = Box::new(std::array::from_fn(|_| None));
        for (index, body) in indices.into_iter().zip(published) {
            entries[index] = Some(body);
            #[cfg(feature = "jit-preview")]
            jgenesis_common::jit::record_cache_event(jgenesis_common::jit::CacheEvent::Compile);
        }
        let bank = std::rc::Rc::new(CodeBank { entries });
        banks.borrow_mut()[high] = Some(bank.clone());
        Some(bank)
    })
}

#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
mod fused;
