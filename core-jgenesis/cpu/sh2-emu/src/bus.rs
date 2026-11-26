//! SH-2 bus interface
//!
//! Implementations can assume that all addresses are masked to the lowest 29 bits (`address & 0x1FFFFFFF`)
//! because the highest 3 bits are only used internally

use crate::debug::Sh2Debugger;
use crate::disassemble;
use crate::disassemble::DisassembledInstruction;
use crate::instructions::OpcodeTable;
use bincode::{Decode, Encode};
use std::fmt::{Display, Formatter};

#[derive(Debug, Clone, Copy, Encode, Decode)]
pub enum AccessContext {
    Fetch,
    Data { pc: u32, opcode: u16 },
    InterruptVector,
    Dma { channel: usize },
}

impl Display for AccessContext {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Fetch => {
                write!(f, "Opcode fetch")
            }
            &Self::Data { pc, opcode } => {
                let mut instruction = DisassembledInstruction::new();
                disassemble::disassemble_into(pc, opcode, &mut instruction);
                write!(f, "PC={pc:08X}, opcode={opcode:04X}, instruction='{}'", instruction.text)
            }
            Self::InterruptVector => write!(f, "Interrupt vector fetch"),
            Self::Dma { channel } => write!(f, "DMA channel {channel}"),
        }
    }
}

pub struct OpSize;

// For cases where size can't be passed as a const generic (e.g. dyn-compatible trait methods)
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OpSizeEnum {
    Byte,
    Word,
    Longword,
}

impl OpSize {
    pub const BYTE: u8 = 0;
    pub const WORD: u8 = 1;
    pub const LONGWORD: u8 = 2;

    /// # Panics
    ///
    /// Panics if `SIZE` is not a valid `OpSize` value
    #[must_use]
    #[inline(always)]
    pub fn display<const SIZE: u8>() -> &'static str {
        match SIZE {
            Self::BYTE => "byte",
            Self::WORD => "word",
            Self::LONGWORD => "longword",
            _ => panic!("invalid size {SIZE}"),
        }
    }

    /// # Panics
    ///
    /// Panics if `SIZE` is not a valid `OpSize` value
    #[must_use]
    #[inline(always)]
    pub fn mask<const SIZE: u8>() -> u32 {
        match SIZE {
            Self::BYTE => 0xFF,
            Self::WORD => 0xFFFF,
            Self::LONGWORD => 0xFFFFFFFF,
            _ => panic!("invalid size {SIZE}"),
        }
    }

    /// # Panics
    ///
    /// Panics if `SIZE` is not a valid `OpSize` value
    #[must_use]
    #[inline(always)]
    pub fn enum_value<const SIZE: u8>() -> OpSizeEnum {
        match SIZE {
            Self::BYTE => OpSizeEnum::Byte,
            Self::WORD => OpSizeEnum::Word,
            Self::LONGWORD => OpSizeEnum::Longword,
            _ => panic!("invalid size {SIZE}"),
        }
    }
}

/// A bus explicitly opts in to a scoped native fetch/cycle fast path.
/// Raw pointers remain live only while the caller exclusively borrows this bus.
#[derive(Clone, Copy)]
pub struct NativeBusView {
    pub cycle_counter: *mut u64,
    pub cycle_limit: *const u64,
    /// Side-effect-free external fetch range, expressed in guest P1 addresses.
    /// Storage contains native-endian u16 values, not guest byte order.
    pub fetch_start: u32,
    pub fetch_end: u32,
    pub fetch_words: *const u16,
    pub fetch_cycles: u64,
}

pub trait BusInterface: Sized {
    /// Debug view type; if not implemented, set to [`crate::debug::DummySh2Debugger`]
    type DebugView<'a>: Sh2Debugger
    where
        Self: 'a;

    fn read<const SIZE: u8>(&mut self, address: u32, ctx: AccessContext) -> u32;

    fn read_byte(&mut self, address: u32, ctx: AccessContext) -> u8 {
        self.read::<{ OpSize::BYTE }>(address, ctx) as u8
    }

    fn read_word(&mut self, address: u32, ctx: AccessContext) -> u16 {
        self.read::<{ OpSize::WORD }>(address, ctx) as u16
    }

    fn read_longword(&mut self, address: u32, ctx: AccessContext) -> u32 {
        self.read::<{ OpSize::LONGWORD }>(address, ctx)
    }

    fn read_cache_line(&mut self, address: u32, ctx: AccessContext) -> [u16; 8];

    fn write<const SIZE: u8>(&mut self, address: u32, value: u32, ctx: AccessContext);

    fn write_byte(&mut self, address: u32, value: u8, ctx: AccessContext) {
        self.write::<{ OpSize::BYTE }>(address, value.into(), ctx);
    }

    fn write_word(&mut self, address: u32, value: u16, ctx: AccessContext) {
        self.write::<{ OpSize::WORD }>(address, value.into(), ctx);
    }

    fn write_longword(&mut self, address: u32, value: u32, ctx: AccessContext) {
        self.write::<{ OpSize::LONGWORD }>(address, value, ctx);
    }

    /// The CPU will halt while this is `true` and then reset when it changes from `true` to `false`
    fn reset(&self) -> bool;

    /// Current external interrupt level from 0 to 15; 0 indicates no interrupt
    fn interrupt_level(&self) -> u8;

    /// DREQ line for DMA channel 0
    fn dma_request_0(&self) -> bool;

    /// DREQ line for DMA channel 1
    fn dma_request_1(&self) -> bool;

    fn acknowledge_dreq_1(&mut self);

    /// Receive a byte from the serial interface, if any
    fn serial_rx(&mut self) -> Option<u8>;

    /// Transmit a byte to the serial interface
    fn serial_tx(&mut self, value: u8);

    fn increment_cycle_counter(&mut self, cycles: u64);

    fn should_stop_execution(&self) -> bool;

    fn debug_view(&mut self) -> Option<Self::DebugView<'_>> {
        None
    }

    /// A side-effect-free performance hint. A short device deadline can make
    /// one native instruction cheaper than setting up a guarded block. The
    /// interpreter's normal fetch/cycle/stop shell is still used in either case.
    fn prefers_native_single_step(&self) -> bool {
        false
    }

    /// # Safety
    /// Returning Some promises that counter addition and `counter >= limit` are exactly
    /// equivalent to increment_cycle_counter/should_stop_execution, with no omitted
    /// side effects. The fetch range must have no MMIO, debugger, synchronization,
    /// or read side effects beyond fetch_cycles. Pointers must remain stable through
    /// every synchronous data-access callback during this borrow. Changes may happen
    /// through those callbacks, but concurrent changes from other threads/observers
    /// are forbidden; callback-free pure runs may hoist guards and combine additions.
    /// The range bounds must be even, ordered, and cover valid native-u16 storage.
    /// Counter/limit pointers must be aligned live u64 values. Debugging must remain
    /// inactive throughout. The caller must not retain pointers after execution returns.
    unsafe fn native_bus_view(&mut self) -> Option<NativeBusView> {
        None
    }

    fn opcode_table<'a>() -> &'a OpcodeTable<Self>;

    /// Opt-in to bit-exact idle-loop skipping (see the `idle` module). A bus that sets this must
    /// implement every `idle_*` method below exactly; it must not be a debugger bus.
    const IDLE_LOOPS: bool = false;

    /// Current cycle counter, as incremented by `increment_cycle_counter`
    fn idle_cycle_counter(&self) -> u64 {
        0
    }

    /// `should_stop_execution()` is exactly `idle_cycle_counter() >= idle_cycle_limit()`
    fn idle_cycle_limit(&self) -> u64 {
        0
    }

    /// Whether `idle_replay_read` supports this (aligned, external) address and size
    fn idle_read_supported(&self, _address: u32, _size: u8) -> bool {
        false
    }

    /// Whether `idle_write_is_noop` supports this (aligned, external) address and size
    fn idle_write_supported(&self, _address: u32, _size: u8) -> bool {
        false
    }

    /// Latest cycle count at which a supported read of this address may start (`None`: never)
    /// without performing any synchronization with other processors, which would change state
    /// that `idle_replay_read` could not undo. Skipping assumes nothing else runs meanwhile.
    fn idle_read_deadline(&self, _address: u32, _size: u8) -> Option<u64> {
        Some(u64::MAX)
    }

    /// Performs a read exactly as `read` would with the cycle counter at `counter` and returns its
    /// value; the cycle counter is left unchanged. Only reads whose side effects are a cycle
    /// charge that does not depend on any state may be supported (synchronization is excluded by
    /// `idle_read_deadline`).
    fn idle_replay_read(&mut self, _address: u32, _size: u8, _counter: u64) -> Option<u32> {
        None
    }

    /// Whether writing `value` would leave all state except the cycle counter unchanged, with a
    /// cycle charge that does not depend on any state
    fn idle_write_is_noop(&mut self, _address: u32, _size: u8, _value: u32) -> bool {
        false
    }

    /// Block-engine differential testing only: called after every executed block with the
    /// instructions left in the slice. Only the recording bus of the verifier implements it.
    #[cfg(feature = "jit-verify")]
    #[doc(hidden)]
    fn jit_checkpoint(&mut self, _ticks_left: u64, _cpu: &crate::Sh2) {}

    /// Whether every read that synchronizes another processor (see `idle_read_deadline`) would run
    /// a processor that is in a verified idle loop and so cannot change any state, no matter how
    /// the run is split into calls.
    fn idle_other_quiescent(&mut self) -> bool {
        false
    }

    /// Performs the synchronization that a read of `address` with the cycle counter at
    /// `read_counter` (before the read's charge) performs, exactly as `read` would.
    fn idle_advance_other(&mut self, _address: u32, _size: u8, _read_counter: u64) {}
}

#[macro_export]
macro_rules! impl_sh2_opcode_table {
    ($bus:ident) => {
        #[inline]
        fn opcode_table<'a>() -> &'a $crate::OpcodeTable<$bus> {
            static TABLE: ::std::sync::LazyLock<$crate::OpcodeTable<$bus>> =
                ::std::sync::LazyLock::new(|| $crate::OpcodeTable::new());

            &*TABLE
        }
    };
}

pub use impl_sh2_opcode_table;
