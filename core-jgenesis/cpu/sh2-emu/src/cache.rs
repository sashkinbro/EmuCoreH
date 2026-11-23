//! SH7604 CPU cache
//!
//! This is a 4-way set-associative cache that uses a pseudo-LRU algorithm for cache replacement.
//!
//! Cache replacement is performed when a cached read misses. The cache is write-through, so writes
//! will only update cache if there is a cache hit.
//!
//! Games that are known to depend on CPU cache emulation (specifically data cache):
//! * WWF Raw (32X) writes to cartridge ROM addresses and expects to be able to read back the written
//!   values from CPU cache. Without cache, it won't correctly populate 32X palette RAM which causes
//!   missing graphics in menus.
//! * Pitfall: The Mayan Adventure (32X) writes to addresses around $00090000 (out-of-bounds in boot
//!   ROM area) and expects to be able to read back the written values from CPU cache. Without cache,
//!   objects/"sprites" that are partially offscreen will not display at all until they are entirely
//!   onscreen.

use crate::debug::CacheDebugState;
use bincode::{Decode, Encode};
use jgenesis_common::debug::{DebugMemoryView, DebugWordsView, Endian};
use jgenesis_common::hostcache::HostCache;
use jgenesis_common::num::{GetBit, U16Ext};
use std::array;

const CACHE_RAM_LEN_WORDS: usize = 4 * 1024 / 2;

const WAYS: usize = 4;

// Cache lines are 16 bytes and there are 4 ways in each cache line
// 4096 / 16 / 4 = 64
const CACHE_ENTRIES: usize = 64;

#[derive(Debug, Clone, Default, Encode, Decode)]
pub struct CacheControlRegister {
    // Specifies which way is accessed when the address array is accessed directly
    pub way: u8,
    pub mode: CacheMode,
    pub disable_data_replacement: bool,
    pub disable_instruction_replacement: bool,
    pub cache_enabled: bool,
}

impl CacheControlRegister {
    fn read(&self) -> u8 {
        (self.way << 6)
            | ((self.mode as u8) << 3)
            | (u8::from(self.disable_data_replacement) << 2)
            | (u8::from(self.disable_instruction_replacement) << 1)
            | u8::from(self.cache_enabled)
    }

    fn write(&mut self, value: u8) {
        self.way = value >> 6;
        self.mode = CacheMode::from_bit(value.bit(3));
        self.disable_data_replacement = value.bit(2);
        self.disable_instruction_replacement = value.bit(1);
        self.cache_enabled = value.bit(0);

        log::trace!("CCR write: {value:02X}");
        log::trace!("  Way specification: {}", self.way);
        log::trace!("  Cache mode: {:?}", self.mode);
        log::trace!("  Cache purged: {}", value.bit(4));
        log::trace!("  Disable data replacement: {}", self.disable_data_replacement);
        log::trace!("  Disable instruction replacement: {}", self.disable_instruction_replacement);
        log::trace!("  Cache enabled: {}", self.cache_enabled);
    }
}

#[derive(Debug, Clone, Encode, Decode)]
struct Way {
    // Tag is address bits 10-28
    tags: [u32; CACHE_ENTRIES],
    valid_bits: u64,
}

impl Way {
    fn new() -> Self {
        Self { tags: array::from_fn(|_| 0), valid_bits: 0 }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Encode, Decode)]
pub enum CacheMode {
    #[default]
    FourWay = 0,
    TwoWay = 1,
}

impl CacheMode {
    fn from_bit(bit: bool) -> Self {
        if bit { Self::TwoWay } else { Self::FourWay }
    }
}

/// Host-only memo of the cache line that satisfied the most recent instruction fetch hit.
///
/// While valid, the line is resident at `ram_base` and its entry's LRU bits already equal the
/// value a hit on it would produce (LRU updates are idempotent), so another fetch from the same
/// line can read cache RAM directly with identical results. Any operation that could change the
/// line's residency, its entry's LRU bits, or the cache configuration invalidates the memo.
#[derive(Debug, Clone, Copy)]
struct FetchMemo {
    // Address bits 4-31 of the memoized line; INVALID_LINE never matches a fetch address
    line: u32,
    ram_base: u16,
}

const INVALID_LINE: u32 = u32::MAX;

/// Host-only per-line change counters used to validate predecoded code blocks.
///
/// `lines[way * 64 + entry]` is bumped whenever the contents, tag or validity of that line may
/// have changed; `conf` is bumped for whole-cache changes (CCR writes, purges). LRU-only updates
/// never bump either. A block recorded while every one of its lines carried a given counter value
/// fetches exactly the same instruction words from exactly the same ways while the values stay.
#[derive(Debug, Clone)]
pub(crate) struct LineGens {
    // Bumped together with every other counter: lets a block skip per-line validation while
    // nothing in the cache has changed since it was last validated
    any: u32,
    conf: u32,
    lines: [u32; WAYS * CACHE_ENTRIES],
}

impl Default for LineGens {
    fn default() -> Self {
        Self { any: 0, conf: 0, lines: [0; WAYS * CACHE_ENTRIES] }
    }
}

impl Default for FetchMemo {
    fn default() -> Self {
        Self { line: INVALID_LINE, ram_base: 0 }
    }
}

#[derive(Debug, Clone, Encode, Decode)]
pub struct CpuCache {
    // Store cache as u16s because the most common fetches are opcodes which are 16-bit
    ram: Box<[u16; CACHE_RAM_LEN_WORDS]>,
    ways: Box<[Way; WAYS]>,
    lru_bits: Box<[u8; CACHE_ENTRIES]>,
    control: CacheControlRegister,
    fetch_memo: HostCache<FetchMemo>,
    // Host-only counter bumped whenever cache contents, residency or configuration change.
    // LRU-only updates (hits) never bump it. Idle-loop skipping uses it to prove that the
    // cached code and data an observed iteration used are still exactly the same.
    epoch: HostCache<u32>,
    gens: HostCache<LineGens>,
}

impl CpuCache {
    pub fn new() -> Self {
        Self {
            ram: vec![0; CACHE_RAM_LEN_WORDS].into_boxed_slice().try_into().unwrap(),
            ways: Box::new(array::from_fn(|_| Way::new())),
            lru_bits: Box::new(array::from_fn(|_| 0)),
            control: CacheControlRegister::default(),
            fetch_memo: HostCache::default(),
            epoch: HostCache::default(),
            gens: HostCache::default(),
        }
    }

    #[inline(always)]
    pub(crate) fn epoch(&self) -> u32 {
        *self.epoch
    }

    #[inline(always)]
    fn bump_epoch(&mut self) {
        *self.epoch = self.epoch.wrapping_add(1);
    }

    #[inline(always)]
    fn bump_line(&mut self, way_idx: usize, entry_idx: usize) {
        let line_gen = &mut self.gens.lines[(way_idx << 6) | entry_idx];
        *line_gen = line_gen.wrapping_add(1);
        self.gens.any = self.gens.any.wrapping_add(1);
    }

    #[inline(always)]
    fn bump_entry(&mut self, entry_idx: usize) {
        for way_idx in 0..WAYS {
            self.bump_line(way_idx, entry_idx);
        }
    }

    #[inline(always)]
    fn bump_conf(&mut self) {
        self.gens.conf = self.gens.conf.wrapping_add(1);
        self.gens.any = self.gens.any.wrapping_add(1);
    }

    #[inline(always)]
    pub(crate) fn any_gen(&self) -> u32 {
        self.gens.any
    }

    #[inline(always)]
    pub(crate) fn conf_gen(&self) -> u32 {
        self.gens.conf
    }

    #[inline(always)]
    pub(crate) fn line_gen(&self, way: u8, entry: u8) -> u32 {
        self.gens.lines[(usize::from(way) << 6) | usize::from(entry)]
    }

    /// Address bits 4-31 of the line the host fetch memo currently covers
    #[inline(always)]
    pub(crate) fn fetch_memo_line(&self) -> u32 {
        self.fetch_memo.line
    }

    /// Side-effect-free instruction fetch hit: `(way, entry, opcode)`
    pub(crate) fn peek_fetch(&self, address: u32) -> Option<(u8, u8, u16)> {
        let (entry, way) = self.lookup(address)?;
        let ram_base = cache_ram_addr(usize::from(way), usize::from(entry)) >> 1;
        Some((way, entry, self.ram[ram_base | (((address as usize) & 0xE) >> 1)]))
    }

    /// The LRU update and fetch memo of an instruction fetch hit on `(way, entry)`, exactly as
    /// `read_instruction_u16_slow` performs them. The caller must have established that the
    /// fetch would hit that way.
    #[inline(always)]
    pub(crate) fn fetch_hit(&mut self, way: u8, entry: u8, line: u32) {
        let (way_idx, entry_idx) = (usize::from(way), usize::from(entry));
        self.update_lru_bits(way_idx, entry_idx);
        let ram_base = cache_ram_addr(way_idx, entry_idx) >> 1;
        *self.fetch_memo = FetchMemo { line, ram_base: ram_base as u16 };
    }

    /// Side-effect-free hit check: the `(entry, way)` a cached access to `address` would hit.
    pub(crate) fn lookup(&self, address: u32) -> Option<(u8, u8)> {
        if !self.control.cache_enabled {
            return None;
        }

        let entry_idx = cache_entry_index(address);
        let tag = tag_address(address);
        (0..4).rev().find_map(|way_idx| {
            (self.ways[way_idx].valid_bits.bit(entry_idx as u8)
                && self.ways[way_idx].tags[entry_idx] == tag)
                .then_some((entry_idx as u8, way_idx as u8))
        })
    }

    /// Applies the LRU update of a hit on `(entry, way)`, exactly as a cached access would.
    pub(crate) fn touch(&mut self, entry: u8, way: u8) {
        let entry_idx = usize::from(entry);
        self.invalidate_fetch_memo_entry(entry_idx);
        self.update_lru_bits(usize::from(way), entry_idx);
    }

    #[inline(always)]
    fn invalidate_fetch_memo(&mut self) {
        self.fetch_memo.line = INVALID_LINE;
    }

    #[inline(always)]
    fn invalidate_fetch_memo_entry(&mut self, entry_idx: usize) {
        if cache_entry_index(self.fetch_memo.line) == entry_idx {
            self.invalidate_fetch_memo();
        }
    }

    /// Cached read for an instruction fetch. Identical to `read_u16`, including LRU updates.
    #[inline(always)]
    pub fn read_instruction_u16(&mut self, address: u32) -> Option<u16> {
        let memo = *self.fetch_memo;
        if address & !0xF == memo.line {
            return Some(self.ram[usize::from(memo.ram_base) | (((address as usize) & 0xE) >> 1)]);
        }

        self.read_instruction_u16_slow(address)
    }

    #[inline(always)]
    fn read_instruction_u16_slow(&mut self, address: u32) -> Option<u16> {
        if !self.control.cache_enabled {
            return None;
        }

        let entry_idx = cache_entry_index(address);
        let tag = tag_address(address);

        for way_idx in (0..4).rev() {
            if self.ways[way_idx].valid_bits.bit(entry_idx as u8)
                && self.ways[way_idx].tags[entry_idx] == tag
            {
                self.update_lru_bits(way_idx, entry_idx);
                let ram_base = cache_ram_addr(way_idx, entry_idx) >> 1;
                *self.fetch_memo = FetchMemo { line: address & !0xF, ram_base: ram_base as u16 };
                return Some(self.ram[ram_base | (((address as usize) & 0xE) >> 1)]);
            }
        }

        None
    }

    #[inline]
    pub fn read_u8(&mut self, address: u32) -> Option<u8> {
        self.cache_read(address, move |cache, way_idx, entry_idx| {
            let address = cache_ram_addr(way_idx, entry_idx) | ((address as usize) & 0xF);
            cache.ram[address >> 1].to_be_bytes()[address & 1]
        })
    }

    #[inline(always)]
    pub fn read_u16(&mut self, address: u32) -> Option<u16> {
        self.cache_read(address, move |cache, way_idx, entry_idx| {
            let address = cache_ram_addr(way_idx, entry_idx) | ((address as usize) & 0xE);
            cache.ram[address >> 1]
        })
    }

    #[inline]
    pub fn read_u32(&mut self, address: u32) -> Option<u32> {
        self.cache_read(address, move |cache, way_idx, entry_idx| {
            let address = (cache_ram_addr(way_idx, entry_idx) | ((address as usize) & 0xC)) >> 1;
            let high_word = cache.ram[address];
            let low_word = cache.ram[address + 1];
            (u32::from(high_word) << 16) | u32::from(low_word)
        })
    }

    #[inline(always)]
    fn cache_read<T>(
        &mut self,
        address: u32,
        read_fn: impl FnOnce(&Self, usize, usize) -> T,
    ) -> Option<T> {
        if !self.control.cache_enabled {
            return None;
        }

        let entry_idx = cache_entry_index(address);
        let tag = tag_address(address);
        self.invalidate_fetch_memo_entry(entry_idx);

        // Iterate in reverse for slightly better performance when cache is in 2-way mode.
        // Per SH7604 documentation, all 4 ways are checked even in 2-way mode; 2-way mode only
        // changes replacement behavior
        for way_idx in (0..4).rev() {
            if self.ways[way_idx].valid_bits.bit(entry_idx as u8)
                && self.ways[way_idx].tags[entry_idx] == tag
            {
                self.update_lru_bits(way_idx, entry_idx);
                return Some(read_fn(self, way_idx, entry_idx));
            }
        }

        None
    }

    pub fn peek(&self, address: u32) -> Option<u16> {
        if !self.control.cache_enabled {
            return None;
        }

        let entry_idx = cache_entry_index(address);
        let tag = tag_address(address);

        for way_idx in (0..4).rev() {
            if self.ways[way_idx].valid_bits.bit(entry_idx as u8)
                && self.ways[way_idx].tags[entry_idx] == tag
            {
                let address = cache_ram_addr(way_idx, entry_idx) | ((address as usize) & 0xE);
                return Some(self.ram[address >> 1]);
            }
        }

        None
    }

    pub fn peek_data_array(&self, address: u32) -> u16 {
        self.ram[((address >> 1) as usize) & (CACHE_RAM_LEN_WORDS - 1)]
    }

    #[inline]
    pub fn should_replace_instruction(&self) -> bool {
        self.control.cache_enabled && !self.control.disable_instruction_replacement
    }

    #[inline]
    pub fn should_replace_data(&self) -> bool {
        self.control.cache_enabled && !self.control.disable_data_replacement
    }

    #[must_use]
    pub fn replace(&mut self, address: u32, cache_line: [u16; 8]) -> u32 {
        let entry_idx = cache_entry_index(address);
        self.invalidate_fetch_memo_entry(entry_idx);
        self.bump_epoch();

        let lru_bits = self.lru_bits[entry_idx];
        let way_idx = match self.control.mode {
            CacheMode::FourWay => {
                usize::from(lru_bits & 0b100110 == 0b000110)
                    | (usize::from(lru_bits & 0b010101 == 0b000001) << 1)
                    | (3 * usize::from(lru_bits & 0b001011 == 0))
            }
            CacheMode::TwoWay => {
                if lru_bits.bit(0) {
                    2
                } else {
                    3
                }
            }
        };

        self.bump_line(way_idx, entry_idx);
        self.ways[way_idx].tags[entry_idx] = tag_address(address);
        self.ways[way_idx].valid_bits |= 1 << entry_idx;
        self.update_lru_bits(way_idx, entry_idx);

        let ram_addr = cache_ram_addr(way_idx, entry_idx) >> 1;
        self.ram[ram_addr..ram_addr + 8].copy_from_slice(&cache_line);

        let cache_line_addr = ((address >> 1) & 7 & !1) as usize;
        let high: u32 = cache_line[cache_line_addr].into();
        let low: u32 = cache_line[cache_line_addr + 1].into();
        low | (high << 16)
    }

    #[inline(always)]
    fn update_lru_bits(&mut self, way_idx: usize, entry_idx: usize) {
        // Bit 5: 0 -> 1
        // Bit 4: 0 -> 2
        // Bit 3: 0 -> 3
        // Bit 2: 1 -> 2
        // Bit 1: 1 -> 3
        // Bit 0: 2 -> 3
        let (and_mask, or_mask) = match way_idx {
            // Clear bits 5-3
            0 => (!0b111000, 0b000000),
            // Clear bits 2-1 and set bit 5
            1 => (!0b000110, 0b100000),
            // Clear bit 0 and set bits 4 and 2
            2 => (!0b000001, 0b010100),
            // Set bits 3, 1, and 0
            3 => (!0b000000, 0b001011),
            _ => panic!("Invalid way index, should be 0-3: {way_idx}"),
        };

        self.lru_bits[entry_idx] &= and_mask;
        self.lru_bits[entry_idx] |= or_mask;
    }

    pub fn write_through_u8(&mut self, address: u32, value: u8) {
        self.cache_write_through(address, move |cache, way_idx, entry_idx| {
            let address = cache_ram_addr(way_idx, entry_idx) | ((address as usize) & 0xF);
            let old = cache.ram[address >> 1];
            if !address.bit(0) {
                cache.ram[address >> 1].set_msb(value);
            } else {
                cache.ram[address >> 1].set_lsb(value);
            }
            if cache.ram[address >> 1] != old {
                cache.bump_epoch();
                cache.bump_line(way_idx, entry_idx);
            }
        });
    }

    pub fn write_through_u16(&mut self, address: u32, value: u16) {
        self.cache_write_through(address, move |cache, way_idx, entry_idx| {
            let address = cache_ram_addr(way_idx, entry_idx) | ((address as usize) & 0xE);
            if cache.ram[address >> 1] != value {
                cache.ram[address >> 1] = value;
                cache.bump_epoch();
                cache.bump_line(way_idx, entry_idx);
            }
        });
    }

    pub fn write_through_u32(&mut self, address: u32, value: u32) {
        self.cache_write_through(address, move |cache, way_idx, entry_idx| {
            let address = (cache_ram_addr(way_idx, entry_idx) | ((address as usize) & 0xC)) >> 1;
            let (high, low) = ((value >> 16) as u16, value as u16);
            if cache.ram[address] != high || cache.ram[address + 1] != low {
                cache.ram[address] = high;
                cache.ram[address + 1] = low;
                cache.bump_epoch();
                cache.bump_line(way_idx, entry_idx);
            }
        });
    }

    #[inline(always)]
    fn cache_write_through(&mut self, address: u32, set_fn: impl FnOnce(&mut Self, usize, usize)) {
        if !self.control.cache_enabled {
            return;
        }

        let entry_idx = cache_entry_index(address);
        let tag = tag_address(address);
        self.invalidate_fetch_memo_entry(entry_idx);

        // Iterate in reverse for slightly better performance when cache is in 2-way mode
        for way_idx in (0..4).rev() {
            if self.ways[way_idx].valid_bits.bit(entry_idx as u8)
                && self.ways[way_idx].tags[entry_idx] == tag
            {
                self.update_lru_bits(way_idx, entry_idx);
                set_fn(self, way_idx, entry_idx);
                return;
            }
        }
    }

    // $FFFFFE92: CCR (Cache control register)
    pub fn read_control(&self) -> u8 {
        self.control.read()
    }

    // $FFFFFE92: CCR (Cache control register)
    pub fn write_control(&mut self, value: u8) {
        self.invalidate_fetch_memo();
        self.bump_epoch();
        self.bump_conf();
        self.control.write(value);

        if value.bit(4) {
            self.purge_all();
        }
    }

    pub fn purge_all(&mut self) {
        self.invalidate_fetch_memo();
        self.bump_epoch();
        self.bump_conf();
        for way in self.ways.as_mut() {
            way.valid_bits = 0;
        }

        self.lru_bits.fill(0);
    }

    // A29-31 = 010
    pub fn associative_purge(&mut self, address: u32) {
        // Invalidates a single cache line
        let idx = cache_entry_index(address);
        self.invalidate_fetch_memo_entry(idx);
        self.bump_epoch();
        self.bump_entry(idx);
        let mask = !(1 << idx);
        for way in self.ways.as_mut() {
            way.valid_bits &= mask;
        }

        // TODO should associative purge clear the LRU bits?
        self.lru_bits[idx] = 0;
    }

    // A29-31 = 011
    pub fn read_address_array(&self, address: u32) -> u32 {
        let entry_idx = cache_entry_index(address);
        let way_idx = self.control.way as usize;

        (u32::from(self.ways[way_idx].valid_bits.bit(entry_idx as u8)) << 1)
            | (u32::from(self.lru_bits[entry_idx]) << 3)
            | (self.ways[way_idx].tags[entry_idx] << 10)
    }

    // A29-31 = 011
    pub fn write_address_array(&mut self, address: u32, value: u32) {
        self.invalidate_fetch_memo();
        self.bump_epoch();
        let entry_idx = cache_entry_index(address);
        self.bump_entry(entry_idx);
        let way_idx = self.control.way as usize;
        let tag = tag_address(address);
        let valid = address.bit(1);
        let lru_bits = ((value >> 3) & 0x3F) as u8;

        if valid {
            self.ways[way_idx].valid_bits |= 1 << entry_idx;
        } else {
            self.ways[way_idx].valid_bits &= !(1 << entry_idx);
        }

        self.ways[way_idx].tags[entry_idx] = tag;
        self.lru_bits[entry_idx] = lru_bits;
    }

    // A29-31 = 110
    pub fn read_data_array_u8(&self, address: u32) -> u8 {
        let word = self.ram[((address >> 1) as usize) & (CACHE_RAM_LEN_WORDS - 1)];
        word.to_be_bytes()[(address & 1) as usize]
    }

    // A29-31 = 110
    pub fn read_data_array_u16(&self, address: u32) -> u16 {
        self.ram[((address >> 1) as usize) & (CACHE_RAM_LEN_WORDS - 1)]
    }

    // A29-31 = 110
    pub fn read_data_array_u32(&self, address: u32) -> u32 {
        let address = ((address >> 1) as usize) & (CACHE_RAM_LEN_WORDS - 1) & !1;
        let high_word = self.ram[address];
        let low_word = self.ram[address + 1];
        (u32::from(high_word) << 16) | u32::from(low_word)
    }

    // A29-31 = 110
    pub fn write_data_array_u8(&mut self, address: u32, value: u8) {
        self.bump_epoch();
        let word_addr = ((address >> 1) as usize) & (CACHE_RAM_LEN_WORDS - 1);
        self.bump_line(word_addr >> 9, (word_addr >> 3) & 0x3F);
        if !address.bit(0) {
            self.ram[word_addr].set_msb(value);
        } else {
            self.ram[word_addr].set_lsb(value);
        }
    }

    // A29-31 = 110
    pub fn write_data_array_u16(&mut self, address: u32, value: u16) {
        self.bump_epoch();
        let word_addr = ((address >> 1) as usize) & (CACHE_RAM_LEN_WORDS - 1);
        self.bump_line(word_addr >> 9, (word_addr >> 3) & 0x3F);
        self.ram[((address >> 1) as usize) & (CACHE_RAM_LEN_WORDS - 1)] = value;
    }

    // A29-31 = 110
    pub fn write_data_array_u32(&mut self, address: u32, value: u32) {
        self.bump_epoch();
        let address = ((address >> 1) as usize) & (CACHE_RAM_LEN_WORDS - 1) & !1;
        self.bump_line(address >> 9, (address >> 3) & 0x3F);
        self.ram[address] = (value >> 16) as u16;
        self.ram[address + 1] = value as u16;
    }

    pub fn debug_view(&mut self) -> impl DebugMemoryView {
        // The debugger can rewrite any cache RAM word
        self.bump_conf();
        DebugWordsView(self.ram.as_mut_slice(), Endian::Big)
    }

    pub(crate) fn debug_state(&self) -> CacheDebugState {
        CacheDebugState {
            enabled: self.control.cache_enabled,
            instruction_replacement_enabled: !self.control.disable_instruction_replacement,
            data_replacement_enabled: !self.control.disable_data_replacement,
            mode: self.control.mode,
        }
    }
}

#[inline(always)]
fn cache_ram_addr(way_idx: usize, entry_idx: usize) -> usize {
    (way_idx << 10) | (entry_idx << 4)
}

#[inline(always)]
fn cache_entry_index(address: u32) -> usize {
    // Cache is indexed using address bits 4-9
    ((address as usize) >> 4) & 0x3F
}

#[inline(always)]
fn tag_address(address: u32) -> u32 {
    // Cache entries are tagged using address bits 10-28
    (address & 0x1FFFFFFF) >> 10
}

/// Explicit native ABI; no generated code relies on CpuCache/Way Rust layout.
#[cfg(all(
    feature = "native-arm64",
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
pub(crate) struct NativeCacheView {
    pub enabled: *const bool,
    pub ram: *const u16,
    pub lru: *mut u8,
    pub tags: [*const u32; 4],
    pub valid: [*const u64; 4],
}
#[cfg(all(
    feature = "native-arm64",
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
impl CpuCache {
    /// The owning Sh2 remains exclusively borrowed throughout generated execution.
    /// Cache callbacks mutate these live values but never replace their allocations.
    pub(crate) fn native_view(&mut self) -> NativeCacheView {
        // Generated code may update LRU bits without going through CpuCache methods
        self.invalidate_fetch_memo();
        // Generated code can also change residency and contents behind the cache's back
        self.bump_epoch();
        self.bump_conf();
        NativeCacheView {
            enabled: &self.control.cache_enabled,
            ram: self.ram.as_ptr(),
            lru: self.lru_bits.as_mut_ptr(),
            tags: std::array::from_fn(|way| self.ways[way].tags.as_ptr()),
            valid: std::array::from_fn(|way| &self.ways[way].valid_bits as *const u64),
        }
    }
}
