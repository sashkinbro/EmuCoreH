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
    if let Some(code) = register_alu::emit(opcode) {
        return Some(code);
    }
    if opcode == 0x4E71 {
        return Some(vec![0xD65F03C0]);
    }
    let reg;
    let mut code;
    let mut size = if opcode & 0xFFF8 == 0x4880 { 2 } else { 4 };
    let mut write = true;
    if opcode & 0xF100 == 0x7000 {
        reg = u32::from((opcode >> 9) & 7);
        let imm = opcode as u8 as i8 as i32 as u32;
        code = vec![0x52800001 | ((imm & 0xFFFF) << 5), 0x72A00001 | ((imm >> 16) << 5)];
    } else {
        reg = u32::from(opcode & 7);
        let unary = opcode & 0xFFF8;
        let op = match unary {
            0x4840 => 0x13814021, // ror w1,w1,#16
            0x4880 => 0x13001C21, // sxtb w1,w1 (write low word only)
            0x48C0 => 0x13003C21, // sxth w1,w1
            0x4200 | 0x4240 | 0x4280 | 0x4600 | 0x4640 | 0x4680 | 0x4A00 | 0x4A40 | 0x4A80 => {
                size = 1 << ((opcode >> 6) & 3);
                write = opcode & 0xFF00 != 0x4A00;
                match opcode & 0xFF00 {
                    0x4200 => 0x2A1F03E1, // mov w1,wzr (CLR)
                    0x4600 => 0x2A2103E1, // mvn w1,w1 (NOT)
                    _ => 0x2A0103E1,      // mov w1,w1 (TST; leaves register untouched)
                }
            }
            _ => return None,
        };
        code = vec![0xB9400001 | (reg << 10), op];
    }
    // x0: repr(C) Context. w1=result; w2=CCR; w3/w4 scratch.
    // Flags are computed by native instructions; preserve X, clear V/C.
    if write {
        code.push(match size {
            1 => 0x39000001 | (reg << 12), // strb: preserve upper 24 bits
            2 => 0x79000001 | (reg << 11), // strh: preserve upper 16 bits
            _ => 0xB9000001 | (reg << 10),
        });
    }
    if size != 4 {
        code.push(if size == 1 { 0x13001C21 } else { 0x13003C21 });
    }
    code.extend([
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

// Register CLR.L and NOT.L take six cycles; TST and the existing operations take four.
// Called only after the emitter has accepted the exact register-only opcode.
pub(crate) fn instruction_cycles(opcode: u16) -> u32 {
    if let Some(cycles) = register_alu::cycles(opcode) {
        return cycles;
    }
    if matches!(opcode & 0xFFF8, 0x4280 | 0x4680) { 6 } else { 4 }
}

// The complete 16-bit opcode space has only 512 banks. Retain each bank once
// rather than evicting individual bodies from a shared RX mapping. Apart from
// avoiding cache thrash, this makes hot positive/negative lookups direct indexing.
#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
const OPCODE_BANKS: usize = 512;
#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
const MAX_BANK_BYTES: usize = 128 * 1024;

#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
#[derive(Debug)]
struct HostCodeCache<Code> {
    // None means uncompiled bank; a resident bank's None entries are unsupported
    // opcodes. Each PublishedCode retains its original immutable RX mapping.
    banks: Box<[Option<Box<[Option<Code>; 128]>>; OPCODE_BANKS]>,
    #[cfg(test)]
    resident_banks: usize,
}

#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
impl<Code> Default for HostCodeCache<Code> {
    fn default() -> Self {
        Self {
            banks: Box::new(std::array::from_fn(|_| None)),
            #[cfg(test)]
            resident_banks: 0,
        }
    }
}

#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
impl<Code> HostCodeCache<Code> {
    fn lookup_with(
        &mut self,
        opcode: u16,
        emit: impl Fn(u16) -> Option<Vec<u32>>,
        publish: &mut impl FnMut(&[&[u32]]) -> Option<Vec<Code>>,
    ) -> Result<Option<&Code>, ()> {
        let bank_index = usize::from(opcode >> 7);
        let entry_index = usize::from(opcode & 127);
        if self.banks[bank_index].is_none() {
            let base = opcode & !127;
            let mut bodies = Vec::new();
            let mut indices = Vec::new();
            let mut bytes = 0usize;
            for offset in 0..128 {
                if let Some(words) = emit(base + offset) {
                    // Match publish_batch's 16-byte body alignment. The bound
                    // is 64 MiB per cache plus at most 512 runtime pages of
                    // rounding. Exhaustive emitter tests guard opcode coverage.
                    bytes = (bytes + 15) & !15;
                    bytes = bytes.checked_add(words.len().checked_mul(4).ok_or(())?).ok_or(())?;
                    if bytes > MAX_BANK_BYTES {
                        return Err(());
                    }
                    indices.push(usize::from(offset));
                    bodies.push(words);
                }
            }
            let mut entries = Box::new(std::array::from_fn(|_| None));
            if !bodies.is_empty() {
                let slices: Vec<&[u32]> = bodies.iter().map(Vec::as_slice).collect();
                let Some(codes) = publish(&slices) else {
                    #[cfg(feature = "jit-runtime")]
                    jgenesis_common::jit::record_cache_event(
                        jgenesis_common::jit::CacheEvent::Failure,
                    );
                    return Err(());
                };
                if codes.len() != indices.len() {
                    return Err(());
                }
                for (index, code) in indices.into_iter().zip(codes) {
                    entries[index] = Some(code);
                    #[cfg(feature = "jit-runtime")]
                    jgenesis_common::jit::record_cache_event(
                        jgenesis_common::jit::CacheEvent::Compile,
                    );
                }
            }
            self.banks[bank_index] = Some(entries);
            #[cfg(test)]
            {
                self.resident_banks += 1;
            }
        } else {
            #[cfg(feature = "jit-runtime")]
            jgenesis_common::jit::record_cache_event(jgenesis_common::jit::CacheEvent::Hit);
        }
        Ok(self.banks[bank_index].as_ref().expect("resident opcode bank")[entry_index].as_ref())
    }
}

#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
impl HostCodeCache<jgenesis_common::native_code::PublishedCode> {
    fn lookup(
        &mut self,
        opcode: u16,
        emit: fn(u16) -> Option<Vec<u32>>,
    ) -> Result<Option<&jgenesis_common::native_code::PublishedCode>, ()> {
        self.lookup_with(
            opcode,
            emit,
            &mut jgenesis_common::native_code::PublishedCode::publish_batch,
        )
    }
}

#[cfg(feature = "jit-runtime")]
const MAX_LEARNED_BLOCKS: usize = 2048;
#[cfg(feature = "jit-runtime")]
const MAX_LEARNED_BYTES: usize = 32 * 1024 * 1024;
#[cfg(feature = "jit-runtime")]
const MAX_BLOCK_BYTES: usize = 64 * 1024;

#[cfg(feature = "jit-runtime")]
#[derive(Debug)]
struct LearnedBlock<Code> {
    code: Option<Code>,
    negative_trace: Vec<(u32, u16)>,
    referenced: bool,
    mapped_bytes: usize,
}

// Clock replacement keeps the PC lookup constant-time and avoids rescanning the
// whole cache for the least-recent timestamp on every publication. Both resident
// entries and page-rounded executable bytes have independent hard limits.
#[cfg(feature = "jit-runtime")]
#[derive(Debug)]
struct LearnedBlockCache<Code> {
    entries: std::collections::HashMap<u32, LearnedBlock<Code>>,
    clock: std::collections::VecDeque<u32>,
    mapped_bytes: usize,
    page_size: usize,
}

#[cfg(feature = "jit-runtime")]
impl<Code> Default for LearnedBlockCache<Code> {
    fn default() -> Self {
        #[cfg(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux")))]
        let page_size = {
            unsafe extern "C" {
                fn getpagesize() -> i32;
            }
            // Same runtime page size used by PublishedCode::publish_batch.
            usize::try_from(unsafe { getpagesize() }).expect("positive runtime page size")
        };
        #[cfg(not(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"))))]
        let page_size = 16384;
        Self::new(page_size)
    }
}

#[cfg(feature = "jit-runtime")]
impl<Code> LearnedBlockCache<Code> {
    fn new(page_size: usize) -> Self {
        assert!(page_size.is_power_of_two());
        Self {
            entries: std::collections::HashMap::new(),
            clock: std::collections::VecDeque::new(),
            mapped_bytes: 0,
            page_size,
        }
    }

    fn get(&mut self, pc: u32) -> Option<&Code> {
        let entry = self.entries.get_mut(&pc)?;
        entry.referenced = true;
        entry.code.as_ref()
    }

    fn learn(
        &mut self,
        pc: u32,
        trace: &[(u32, u16)],
        emit: impl FnOnce(&[(u32, u16)]) -> Option<Vec<u32>>,
        publish: &mut impl FnMut(&[u32]) -> Option<Code>,
    ) {
        if let Some(entry) = self.entries.get_mut(&pc) {
            if entry.code.is_some() || entry.negative_trace == trace {
                entry.referenced = true;
                return;
            }
            // A formerly unsupported trace may change after self-modification.
            self.entries.remove(&pc);
            self.clock.retain(|&candidate| candidate != pc);
        }
        let words =
            emit(trace).filter(|words| !words.is_empty() && words.len() <= MAX_BLOCK_BYTES / 4);
        let bytes = words
            .as_ref()
            .map_or(0, |words| (4 * words.len() + self.page_size - 1) & !(self.page_size - 1));
        while self.entries.len() >= MAX_LEARNED_BLOCKS
            || self.mapped_bytes + bytes > MAX_LEARNED_BYTES
        {
            let victim = self.clock.pop_front().expect("resident clock candidate");
            let entry = self.entries.get_mut(&victim).expect("resident block");
            if entry.referenced {
                entry.referenced = false;
                self.clock.push_back(victim);
            } else {
                let evicted = self.entries.remove(&victim).expect("resident block");
                self.mapped_bytes -= evicted.mapped_bytes;
                jgenesis_common::jit::record_cache_event(jgenesis_common::jit::CacheEvent::Evict);
            }
        }
        let code = words.as_ref().and_then(|words| publish(words));
        let mapped_bytes = if code.is_some() { bytes } else { 0 };
        if code.is_some() {
            jgenesis_common::jit::record_cache_event(jgenesis_common::jit::CacheEvent::Compile);
        } else if words.is_some() {
            jgenesis_common::jit::record_cache_event(jgenesis_common::jit::CacheEvent::Failure);
        }
        let negative_trace = if code.is_none() { trace.to_vec() } else { Vec::new() };
        self.entries
            .insert(pc, LearnedBlock { code, negative_trace, referenced: true, mapped_bytes });
        self.clock.push_back(pc);
        self.mapped_bytes += mapped_bytes;
    }
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
    cache: HostCodeCache<jgenesis_common::native_code::PublishedCode>,
    #[cfg(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    ))]
    memory_cache: HostCodeCache<jgenesis_common::native_code::PublishedCode>,
    disabled: bool,
    #[cfg(any(test, feature = "jit-preview"))]
    native_instructions: u64,
    #[cfg(feature = "jit-runtime")]
    trace: Vec<(u32, u16)>,
    #[cfg(feature = "jit-runtime")]
    trace_next: Option<u32>,
    #[cfg(all(
        feature = "jit-runtime",
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    ))]
    blocks: LearnedBlockCache<jgenesis_common::native_code::PublishedCode>,
}

impl NativeExecutor {
    #[cfg(feature = "jit-runtime")]
    pub(crate) fn record_instruction(&mut self, pc: u32, opcode: u16, next: u32, continues: bool) {
        if self.trace_next.is_some_and(|expected| expected != pc) {
            self.finish_trace();
        }
        self.trace.push((pc, opcode));
        self.trace_next = Some(next);
        if self.trace.len() == 16 || !continues {
            self.finish_trace();
        }
    }
    #[cfg(feature = "jit-runtime")]
    fn finish_trace(&mut self) {
        #[cfg(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        ))]
        if self.trace.len() >= 2 {
            let pc = self.trace[0].0;
            self.blocks.learn(
                pc,
                &self.trace,
                block::emit,
                &mut jgenesis_common::native_code::PublishedCode::publish,
            );
        }
        self.trace.clear();
        self.trace_next = None;
    }
    #[cfg(feature = "jit-runtime")]
    pub(crate) fn block_entry(&mut self, pc: u32) -> Option<*const std::ffi::c_void> {
        if self.disabled {
            return None;
        }
        #[cfg(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        ))]
        {
            let code = self.blocks.get(pc)?;
            Some(unsafe { code.entry_pointer() })
        }
        #[cfg(not(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        )))]
        {
            let _ = pc;
            None
        }
    }
    #[cfg(feature = "jit-runtime")]
    pub(crate) fn count_block(&mut self, count: u64) {
        #[cfg(any(test, feature = "jit-preview"))]
        {
            self.native_instructions += count;
        }
        #[cfg(not(any(test, feature = "jit-preview")))]
        {
            let _ = count;
        }
    }
    #[must_use]
    pub fn native_instructions(&self) -> u64 {
        #[cfg(any(test, feature = "jit-preview"))]
        {
            self.native_instructions
        }
        #[cfg(not(any(test, feature = "jit-preview")))]
        {
            0
        }
    }
    pub fn disable(&mut self) {
        self.disabled = true;
    }

    pub(crate) fn execute_memory(&mut self, opcode: u16, context: &mut memory::Context) -> bool {
        if self.disabled {
            return false;
        }
        #[cfg(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        ))]
        {
            let code = match self.memory_cache.lookup(opcode, memory::emit) {
                Ok(Some(code)) => code,
                Ok(None) => return false,
                Err(()) => {
                    self.disabled = true;
                    return false;
                }
            };
            // SAFETY: emitted A64 preserves callee-saved registers, retains no pointers,
            // and uses this synchronous stack-owned Context only through its repr(C) ABI.
            unsafe {
                let run: unsafe extern "C" fn(*mut memory::Context) =
                    std::mem::transmute(code.entry_pointer());
                run(context);
            }
            #[cfg(any(test, feature = "jit-preview"))]
            {
                self.native_instructions += 1;
            }
            true
        }
        #[cfg(not(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        )))]
        {
            let _ = (opcode, context);
            false
        }
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
            let code = match self.cache.lookup(opcode, emit) {
                Ok(Some(code)) => code,
                Ok(None) => return false,
                Err(()) => {
                    self.disabled = true;
                    return false;
                }
            };
            let mut ctx = Context { data: *data, ccr: u32::from(*ccr) };
            // SAFETY: our leaf emitter uses caller-saved x0..x4, preserves SP/x19..x30,
            // and accesses only Context's repr(C) array at 0..28 and u32 CCR at 32.
            // Code lives through this synchronous call and retains no guest pointers.
            unsafe {
                let run: unsafe extern "C" fn(*mut Context) =
                    std::mem::transmute(code.entry_pointer());
                run(&mut ctx);
            }
            *data = ctx.data;
            *ccr = ctx.ccr as u8;
            #[cfg(any(test, feature = "jit-preview"))]
            {
                self.native_instructions += 1;
            }
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
    #[cfg(feature = "jit-runtime")]
    #[test]
    fn learned_blocks_current_emitters_fit_individual_limit() {
        let mut max_bytes = 0;
        for opcode in 0..=u16::MAX {
            let trace = std::array::from_fn::<_, 16, _>(|i| (2 * i as u32, opcode));
            if let Some(words) = block::emit(&trace) {
                max_bytes = max_bytes.max(words.len() * 4);
            }
        }
        assert!(max_bytes <= MAX_BLOCK_BYTES, "current block needs {max_bytes} bytes");
        eprintln!("largest 16-instruction repeated-opcode block: {max_bytes} bytes");
    }

    #[cfg(feature = "jit-runtime")]
    #[test]
    fn learned_blocks_reuse_a_working_set_larger_than_256_pcs() {
        let mut cache = LearnedBlockCache::<u32>::new(16384);
        let publications = std::cell::Cell::new(0);
        for _ in 0..3 {
            for pc in 0..1024 {
                let trace = [(pc, 0x4E71), (pc + 2, 0x4E71)];
                cache.learn(pc, &trace, |_| Some(vec![0]), &mut |_| {
                    publications.set(publications.get() + 1);
                    Some(pc)
                });
                assert_eq!(cache.get(pc), Some(&pc));
            }
        }
        assert_eq!(publications.get(), 1024, "each hot PC is published once");
        assert_eq!(cache.entries.len(), 1024);
        assert_eq!(cache.mapped_bytes, 1024 * 16384);
    }

    #[cfg(feature = "jit-runtime")]
    #[test]
    fn learned_blocks_bound_mappings_and_cache_negative_traces() {
        let mut cache = LearnedBlockCache::<u32>::new(16384);
        let attempts = std::cell::Cell::new(0);
        let trace = [(0, 0xFFFF), (2, 0xFFFF)];
        for _ in 0..3 {
            cache.learn(
                0,
                &trace,
                |_| {
                    attempts.set(attempts.get() + 1);
                    None
                },
                &mut |_| None,
            );
        }
        assert_eq!(attempts.get(), 1);
        cache.learn(0, &[(0, 0x4E71), (2, 0x4E71)], |_| Some(vec![0]), &mut |_| Some(0));
        assert_eq!(cache.get(0), Some(&0), "changed code must retry a negative entry");
        for pc in 1..4096 {
            // Larger blocks force byte-budget eviction before entry capacity.
            cache.learn(
                pc,
                &[(pc, 0x4E71), (pc + 2, 0x4E71)],
                |_| Some(vec![0; 8192]),
                &mut |_| Some(pc),
            );
            assert!(cache.entries.len() <= MAX_LEARNED_BLOCKS);
            assert!(cache.mapped_bytes <= MAX_LEARNED_BYTES);
            assert_eq!(cache.clock.len(), cache.entries.len());
        }
        assert_eq!(cache.entries.len(), MAX_LEARNED_BYTES / 32768);
        let before = cache.mapped_bytes;
        cache.learn(9999, &trace, |_| Some(vec![0; MAX_BLOCK_BYTES / 4 + 1]), &mut |_| {
            panic!("oversized block publication")
        });
        assert_eq!(cache.mapped_bytes, before);
    }

    #[test]
    fn opcode_banks_cover_all_current_emitters_with_bounded_publication() {
        for (name, emitter) in
            [("register", emit as fn(u16) -> Option<Vec<u32>>), ("memory", memory::emit)]
        {
            let mut cache = HostCodeCache::<usize>::default();
            let mut publications = 0;
            let mut max_bytes = 0;
            let mut emitted_bytes = 0;
            let mut bodies = 0;
            let mut publish = |slices: &[&[u32]]| {
                publications += 1;
                let mut bytes = 0;
                for words in slices {
                    bytes = ((bytes + 15) & !15) + 4 * words.len();
                }
                max_bytes = max_bytes.max(bytes);
                emitted_bytes += bytes;
                bodies += slices.len();
                Some(slices.iter().map(|body| body.len()).collect())
            };
            // Lookup every opcode twice, including unsupported opcodes. The
            // second pass must use existing banks without emission/publication.
            for _ in 0..2 {
                for opcode in 0..=u16::MAX {
                    let expected = emitter(opcode).map(|words| words.len());
                    assert_eq!(
                        cache.lookup_with(opcode, emitter, &mut publish).unwrap().copied(),
                        expected
                    );
                }
            }
            assert_eq!(cache.resident_banks, OPCODE_BANKS);
            assert!(publications <= OPCODE_BANKS);
            if name == "memory" {
                assert!(bodies > 8192, "exercise the former cache capacity");
            }
            eprintln!(
                "{name}: {bodies} bodies, {publications} publications, {emitted_bytes} bytes, max bank {max_bytes} bytes"
            );
        }
    }

    #[test]
    fn opcode_banks_retain_hot_banks_under_pressure() {
        let mut cache = HostCodeCache::<u16>::default();
        let mut publications = 0;
        let mut publish = |bodies: &[&[u32]]| {
            publications += 1;
            Some(bodies.iter().map(|body| body[0] as u16).collect())
        };
        // 512 full banks contain 65536 distinct accepted instruction bodies,
        // exceeding the old 8192-entry limit. Repeatedly touch the hot pair.
        let emission_calls = std::cell::Cell::new(0);
        let emit = |opcode| {
            emission_calls.set(emission_calls.get() + 1);
            Some(vec![u32::from(opcode)])
        };
        for bank in 0..512 {
            for offset in 0..128 {
                let opcode = bank * 128 + offset;
                assert_eq!(cache.lookup_with(opcode, emit, &mut publish), Ok(Some(&opcode)));
            }
            for opcode in [0, 128] {
                assert_eq!(cache.lookup_with(opcode, emit, &mut publish), Ok(Some(&opcode)));
            }
            assert!(cache.resident_banks <= OPCODE_BANKS);
        }
        assert_eq!(publications, 512, "one publication per new bank; hot banks survive");
        assert_eq!(emission_calls.get(), 65536, "hot lookups never invoke the emitter");
        assert_eq!(cache.resident_banks, OPCODE_BANKS);
        assert!(cache.banks[0].is_some());
        assert!(cache.banks[1].is_some());
        assert!(cache.banks.iter().all(Option::is_some));
    }

    #[test]
    fn opcode_banks_cache_negative_entries_without_publication() {
        let mut cache = HostCodeCache::<u16>::default();
        let calls = std::cell::Cell::new(0);
        let emit = |_| {
            calls.set(calls.get() + 1);
            None
        };
        let mut publish = |_: &[&[u32]]| -> Option<Vec<u16>> {
            panic!("empty banks must not allocate executable memory")
        };
        for opcode in 0..128 {
            assert_eq!(cache.lookup_with(opcode, emit, &mut publish), Ok(None));
        }
        assert_eq!(calls.get(), 128, "unsupported opcodes are examined once per bank");
    }

    #[test]
    fn opcode_banks_reject_oversize_or_failed_publications() {
        let mut cache = HostCodeCache::<u16>::default();
        let mut publish = |_: &[&[u32]]| None;
        assert_eq!(cache.lookup_with(0, |_| Some(vec![0]), &mut publish), Err(()));
        assert_eq!(cache.resident_banks, 0);
        assert_eq!(
            cache.lookup_with(0, |_| Some(vec![0; MAX_BANK_BYTES / 4 + 1]), &mut publish),
            Err(())
        );
        assert_eq!(cache.resident_banks, 0);
    }

    #[test]
    fn emits_only_register_operations() {
        for r in 0..8 {
            for imm in 0..256 {
                assert!(emit(0x7000 | (r << 9) | imm).is_some());
            }
            for op in [
                0x4840, 0x4880, 0x48C0, 0x4200, 0x4240, 0x4280, 0x4600, 0x4640, 0x4680, 0x4A00,
                0x4A40, 0x4A80,
            ] {
                assert!(emit(op | r).is_some());
            }
        }
        assert_eq!(emit(0x4E71), Some(vec![0xD65F03C0]));
        for op in [
            0x7100, 0x4E72, 0x4E75, 0x6000, 0x3010, 0x48D0, 0x4848, 0x4210, 0x4250, 0x4290, 0x4610,
            0x4650, 0x4690, 0x4A10, 0x4A50, 0x4A90, 0x42C0, 0x46C0, 0x4AC0,
        ] {
            assert_eq!(emit(op), None, "{op:04X}");
        }
    }
}

#[cfg(all(test, feature = "bincode"))]
mod differential;

mod register_alu;

pub(crate) mod memory;

#[cfg(feature = "jit-runtime")]
mod block;
