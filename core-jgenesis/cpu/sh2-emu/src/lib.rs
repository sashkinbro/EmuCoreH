//! Emulation core for the Hitachi SH-2 CPU
//!
//! Note that this core does not track timing. Callers can compute timing by assuming 1 cycle per
//! instruction plus memory access delays, using the `BusInterface` implementation to record memory
//! accesses.

pub mod bus;
mod cache;
pub mod debug;
mod disassemble;
mod divu;
mod dma;
mod frt;
mod instructions;
#[cfg(feature = "native-arm64")]
pub mod native;
mod registers;
mod sci;
mod wdt;
#[cfg(test)]
mod interpreter_tests;

use crate::bus::{AccessContext, BusInterface};
use crate::cache::CpuCache;
use crate::debug::{BusDebugExt, Sh2Debugger};
use crate::divu::DivisionUnit;
use crate::dma::DmaController;
use crate::frt::FreeRunTimer;
use crate::registers::{Sh2Registers, Sh7604Registers};
use crate::sci::SerialInterface;
use crate::wdt::WatchdogTimer;
use bincode::{Decode, Encode};
pub use disassemble::{
    DisassembledInstruction, Displacement, MemoryAccess, MemoryAccessSize, ReadType,
    disassemble_into,
};
pub use instructions::OpcodeTable;
use jgenesis_common::debug::DebugMemoryView;
use std::env;
use std::fmt::Debug;

pub use cache::CacheMode;
pub use dma::{DmaAddressMode, DmaChannel, DmaChannelControl, DmaTransferUnit};

const RESET_PC_VECTOR: u32 = 0x00000000;
const RESET_SP_VECTOR: u32 = 0x00000004;

const RESET_INTERRUPT_MASK: u8 = 15;
const RESET_VBR: u32 = 0x00000000;

const BASE_IRL_VECTOR_NUMBER: u32 = 64;

// R15 is the hardware stack pointer
const SP: usize = 15;

// Only A0-28 are visible externally; A29-31 are handled internally
const EXTERNAL_ADDRESS_MASK: u32 = 0x1FFFFFFF;
const CACHE_LINE_MASK: u32 = EXTERNAL_ADDRESS_MASK & !0xF;

#[derive(Debug, Clone, Encode, Decode)]
pub struct Sh2 {
    registers: Sh2Registers,
    cache: CpuCache,
    sh7604: Sh7604Registers,
    dmac: DmaController,
    free_run_timer: FreeRunTimer,
    watchdog_timer: WatchdogTimer,
    divu: DivisionUnit,
    serial: SerialInterface,
    reset_pending: bool,
    data_ctx: AccessContext,
    name: String,
    trace_log_enabled: bool,
}

fn trace_log_enabled(name: &str) -> bool {
    match env::var("SH2_LOG") {
        Ok(log_name) => name == log_name,
        Err(_) => true,
    }
}

// Both backends share reset, DMA, delay-slot and interrupt scheduling.
trait ExecutionBody<Bus: BusInterface> {
    fn opcode(&mut self, cpu: &mut Sh2, bus: &mut Bus, opcode: u16) -> bool;
    fn block(&mut self, _cpu: &mut Sh2, _bus: &mut Bus, _ticks: u64) -> (u64, bool) {
        (0, false)
    }
}

struct Interpreter;
impl<Bus: BusInterface> ExecutionBody<Bus> for Interpreter {
    #[inline(always)]
    fn opcode(&mut self, _: &mut Sh2, _: &mut Bus, _: u16) -> bool {
        false
    }
}

impl Sh2 {
    #[must_use]
    #[allow(clippy::missing_panics_doc)]
    pub fn new(name: String) -> Self {
        let trace_log_enabled = trace_log_enabled(&name);

        Self {
            registers: Sh2Registers::default(),
            cache: CpuCache::new(),
            sh7604: Sh7604Registers::new(),
            dmac: DmaController::new(),
            free_run_timer: FreeRunTimer::new(),
            watchdog_timer: WatchdogTimer::new(),
            divu: DivisionUnit::new(),
            serial: SerialInterface::new(name.clone()),
            reset_pending: true,
            data_ctx: AccessContext::Data { pc: 0, opcode: 0 },
            name,
            trace_log_enabled,
        }
    }

    /// Execute up to `ticks` instructions.
    ///
    /// Will not execute any instructions if a reset is performed or an interrupt is handled.
    #[inline]
    pub fn execute<Bus: BusInterface>(&mut self, ticks: u64, bus: &mut Bus) {
        #[cfg(not(feature = "jit-runtime"))]
        {
            self.execute_with_body(ticks, bus, &mut Interpreter)
        }
        #[cfg(feature = "jit-runtime")]
        {
            let cpu = if self.name == "Slave" {
                jgenesis_common::jit::Cpu::SlaveSh2
            } else {
                jgenesis_common::jit::Cpu::MasterSh2
            };
            jgenesis_common::jit::with_cpu(cpu, || {
                if !jgenesis_common::jit::enabled() {
                    return self.execute_with_body(ticks, bus, &mut Interpreter);
                }
                #[cfg(feature = "jit-preview")]
                jgenesis_common::jit::record_dispatch(jgenesis_common::jit::DispatchKind::Slice, 1);
                let key = std::ptr::from_mut(self) as usize;
                let epoch = jgenesis_common::jit::epoch();
                // Remove before entering guest bus calls: a nested other-CPU execution
                // can borrow the registry without borrowing an active executor.
                // Move only a Box: a hot synchronization slice must not copy the
                // complete code-cache owner into and out of the hash table.
                let mut executor = PREVIEW_EXECUTORS.with_borrow_mut(|(generation, map)| {
                    if *generation != epoch {
                        map.clear();
                        *generation = epoch;
                    }
                    map.remove(&key).unwrap_or_default()
                });
                let result = self.execute_with_native(ticks, bus, &mut executor);
                PREVIEW_EXECUTORS.with_borrow_mut(|(generation, map)| {
                    if *generation == epoch {
                        if map.len() >= 8 {
                            map.clear();
                        }
                        map.insert(key, executor);
                    }
                });
                result
            })
        }
    }

    /// Experimental opt-in ARM64 bodies. Fetch, DMA, IRQs, delay slots and cycle accounting
    /// use the same shell as `execute`; unsupported opcodes/hosts fall back to its interpreter.
    #[cfg(feature = "native-arm64")]
    #[inline]
    pub fn execute_with_native<Bus: BusInterface>(
        &mut self,
        ticks: u64,
        bus: &mut Bus,
        executor: &mut native::NativeExecutor,
    ) {
        self.execute_with_body(ticks, bus, executor);
        executor.finish_trace();
    }

    #[inline]
    fn execute_with_body<Bus: BusInterface>(
        &mut self,
        mut ticks: u64,
        bus: &mut Bus,
        body: &mut impl ExecutionBody<Bus>,
    ) {
        if ticks == 0 {
            return;
        }

        if bus.reset() {
            self.reset_pending = true;
            // TODO how long does a reset take?
            bus.increment_cycle_counter(5);
            return;
        }

        if self.reset_pending {
            self.reset_pending = false;

            // First 8 bytes of the address space contain the reset vector and the initial SP
            // TODO use different vectors for manual reset vs. power-on reset? 32X doesn't depend on this
            self.registers.pc = bus.read_longword(RESET_PC_VECTOR, AccessContext::InterruptVector);
            self.registers.next_pc = self.registers.pc.wrapping_add(2);
            self.registers.next_op_in_delay_slot = false;

            self.registers.gpr[SP] =
                bus.read_longword(RESET_SP_VECTOR, AccessContext::InterruptVector);

            self.registers.sr.interrupt_mask = RESET_INTERRUPT_MASK;
            self.registers.vbr = RESET_VBR;

            self.cache.purge_all();

            log::trace!(
                "[{}] Reset SH-2; PC is {:08X} and SP is {:08X}",
                self.name,
                self.registers.pc,
                self.registers.gpr[SP]
            );

            // TODO how long should this take?
            bus.increment_cycle_counter(5);

            return;
        }

        for _ in 0..ticks {
            if !self.try_tick_dma(bus) {
                break;
            }
        }

        // This immutable bus-type table is shared by every instruction in a slice.
        // Resolve its LazyLock once instead of issuing an acquire load per opcode.
        let opcode_table = Bus::opcode_table();

        // Interrupts cannot trigger in a delay slot per the SH7604 hardware manual
        // Before checking for interrupts, make sure the CPU is not in a delay slot
        if self.registers.next_op_in_delay_slot {
            self.execute_single_instruction(bus, body, opcode_table);
            ticks -= 1;
        }

        debug_assert!(
            !self.registers.next_op_in_delay_slot,
            "SH-2 executed two simultaneous delay slot instructions, PC={:08X}",
            self.registers.pc
        );

        let external_interrupt_level = bus.interrupt_level();
        let internal_interrupt_level = self.sh7604.internal_interrupt.priority;
        let interrupt_mask = self.registers.sr.interrupt_mask;

        if external_interrupt_level > interrupt_mask
            && external_interrupt_level >= internal_interrupt_level
        {
            let vector_number = BASE_IRL_VECTOR_NUMBER + u32::from(external_interrupt_level >> 1);
            self.handle_exception(Some(external_interrupt_level), vector_number, bus);
            return;
        }

        if internal_interrupt_level > interrupt_mask {
            let vector_number: u32 = self.sh7604.internal_interrupt.vector_number.into();
            self.handle_exception(Some(internal_interrupt_level), vector_number, bus);
            return;
        }

        while ticks != 0 {
            let (executed, stopped) = body.block(self, bus, ticks);
            if executed != 0 {
                ticks -= executed;
                if stopped {
                    return;
                }
                continue;
            }
            self.execute_single_instruction(bus, body, opcode_table);
            ticks -= 1;
            // Interrupts are sampled at the start of every execute() slice and
            // the slice length is 50 instructions, so polling only the cycle
            // budget here removes two global register reads per guest
            // instruction while keeping IRQ latency below two microseconds at
            // the 32X's 23 MHz SH-2 clock.
            if bus.should_stop_execution() {
                return;
            }
        }
    }

    #[inline(always)]
    fn execute_single_instruction<Bus: BusInterface>(
        &mut self,
        bus: &mut Bus,
        body: &mut impl ExecutionBody<Bus>,
        opcode_table: &OpcodeTable<Bus>,
    ) {
        let opcode = self.prepare_instruction(bus);
        let native = body.opcode(self, bus, opcode);
        #[cfg(feature = "jit-preview")]
        jgenesis_common::jit::record_opcode(opcode, native);
        if !native {
            self.execute_interpreter_opcode(opcode, bus, opcode_table);
        }
        bus.increment_cycle_counter(1);
    }

    #[inline(always)]
    fn execute_interpreter_opcode<Bus: BusInterface>(
        &mut self,
        opcode: u16,
        bus: &mut Bus,
        opcode_table: &OpcodeTable<Bus>,
    ) {
        // Reuse the ordinary interpreter methods for common polling/ALU families,
        // avoiding the table call and the secondary 8/C-family decode wrapper.
        // Fetch, delay slots, IRQ sampling and cycle accounting stay in the shell.
        match opcode & 0xFF00 {
            0xC500 => self.mov_w_disp_gbr_r0(opcode, bus),
            0xC800 => self.tst_imm_r0(opcode),
            0x8900 => self.bt(opcode, bus),
            _ if opcode & 0xF00F == 0x6008 => self.swap_b(opcode),
            _ => opcode_table.decode(opcode)(self, opcode, bus),
        }
    }

    #[inline(always)]
    fn prepare_instruction<Bus: BusInterface>(&mut self, bus: &mut Bus) -> u16 {
        let pc = self.registers.pc;
        let opcode = self.read_opcode(pc, bus);

        bus.check_execute(pc, opcode, self);

        self.registers.pc = self.registers.next_pc;
        self.registers.next_pc = self.registers.pc.wrapping_add(2);
        self.registers.next_op_in_delay_slot = false;

        self.data_ctx = AccessContext::Data { pc, opcode };

        if log::log_enabled!(log::Level::Trace) && self.trace_log_enabled {
            let mut disassembled = DisassembledInstruction::new();
            disassemble_into(pc, opcode, &mut disassembled);
            log::trace!(
                "[{}] Executing opcode {opcode:04X} at PC {pc:08X}: {}",
                self.name,
                disassembled.text
            );
            log::trace!("  Registers: {:08X?}", self.registers.gpr);
            log::trace!(
                "  GBR={:08X} VBR={:08X} PR={:08X}",
                self.registers.gbr,
                self.registers.vbr,
                self.registers.pr
            );
            log::trace!("  SR={:?}", self.registers.sr);
        }

        opcode
    }
    /// Advance internal peripherals by `system_cycles`, specifically the watchdog timer (WDT) and
    /// the serial interface (SCI). Also updates internal interrupt state.
    #[inline]
    pub fn tick_peripherals<Bus: BusInterface>(&mut self, system_cycles: u64, bus: &mut Bus) {
        self.watchdog_timer.tick(system_cycles);
        self.serial.process(system_cycles, bus);
        self.update_internal_interrupt_level();
    }

    fn read_byte<Bus: BusInterface>(&mut self, address: u32, bus: &mut Bus) -> u8 {
        bus.check_read_byte(address, self);

        match address >> 29 {
            0 => self.cached_read_byte(address, bus),
            1 => bus.apply_read_byte(address & EXTERNAL_ADDRESS_MASK, self.data_ctx, self),
            2 => {
                self.cache.associative_purge(address);
                0
            }
            3..=5 => {
                log::warn!(
                    "SH-2 {:?} invalid byte read: {address:08X}, ctx: {}",
                    self.name,
                    self.data_ctx
                );
                0
            }
            6 => self.cache.read_data_array_u8(address),
            7 => self.read_internal_register_byte(address, bus),
            _ => unreachable!("u32 >> 29 is always 0-7"),
        }
    }

    fn cached_read_byte<Bus: BusInterface>(&mut self, address: u32, bus: &mut Bus) -> u8 {
        if let Some(value) = self.cache.read_u8(address) {
            return value;
        }

        if self.cache.should_replace_data() {
            let cache_line =
                bus.apply_read_cache_line(address & CACHE_LINE_MASK, self.data_ctx, self);
            let longword = self.cache.replace(address, cache_line);
            longword.to_be_bytes()[(address & 3) as usize]
        } else {
            bus.apply_read_byte(address & EXTERNAL_ADDRESS_MASK, self.data_ctx, self)
        }
    }

    fn read_word<Bus: BusInterface>(&mut self, address: u32, bus: &mut Bus) -> u16 {
        self.read_word_generic::<false, _>(address, bus)
    }

    #[inline(always)]
    fn read_opcode<Bus: BusInterface>(&mut self, address: u32, bus: &mut Bus) -> u16 {
        self.read_word_generic::<true, _>(address, bus)
    }

    #[inline(always)]
    fn read_word_generic<const INSTRUCTION: bool, Bus: BusInterface>(
        &mut self,
        address: u32,
        bus: &mut Bus,
    ) -> u16 {
        if !INSTRUCTION {
            bus.check_read_word(address, self);
        }

        match address >> 29 {
            0 => self.cached_read_word::<INSTRUCTION, _>(address, bus),
            1 => {
                let ctx = if INSTRUCTION { AccessContext::Fetch } else { self.data_ctx };
                bus.apply_read_word(address & EXTERNAL_ADDRESS_MASK, ctx, self)
            }
            2 => {
                self.cache.associative_purge(address);
                0
            }
            3..=5 => {
                let ctx = if INSTRUCTION { AccessContext::Fetch } else { self.data_ctx };
                log::warn!("SH-2 {:?} invalid word read: {address:08X}, ctx: {ctx}", self.name);
                0
            }
            6 => self.cache.read_data_array_u16(address),
            7 => self.read_internal_register_word(address),
            _ => unreachable!("u32 >> 29 is always 0-7"),
        }
    }

    #[inline(always)]
    fn cached_read_word<const INSTRUCTION: bool, Bus: BusInterface>(
        &mut self,
        address: u32,
        bus: &mut Bus,
    ) -> u16 {
        if let Some(value) = self.cache.read_u16(address) {
            return value;
        }

        if (INSTRUCTION && self.cache.should_replace_instruction())
            || (!INSTRUCTION && self.cache.should_replace_data())
        {
            let ctx = if INSTRUCTION { AccessContext::Fetch } else { self.data_ctx };
            let cache_line = bus.apply_read_cache_line(address & CACHE_LINE_MASK, ctx, self);
            let longword = self.cache.replace(address, cache_line);
            (longword >> (16 * (((address >> 1) & 1) ^ 1))) as u16
        } else {
            let ctx = if INSTRUCTION { AccessContext::Fetch } else { self.data_ctx };
            bus.apply_read_word(address & EXTERNAL_ADDRESS_MASK, ctx, self)
        }
    }

    fn read_longword<Bus: BusInterface>(&mut self, address: u32, bus: &mut Bus) -> u32 {
        bus.check_read_longword(address, self);

        match address >> 29 {
            0 => self.cached_read_longword(address, bus),
            1 => bus.apply_read_longword(address & EXTERNAL_ADDRESS_MASK, self.data_ctx, self),
            2 => {
                // FIFA Soccer 96 reads from associative purge addresses and doesn't use the values read
                // Seems like it expects reads to purge cache lines in addition to writes?
                self.cache.associative_purge(address);
                0
            }
            3 => self.cache.read_address_array(address),
            4 | 5 => {
                log::warn!(
                    "SH-2 {:?} invalid longword read: {address:08X}, ctx: {}",
                    self.name,
                    self.data_ctx
                );
                0
            }
            6 => self.cache.read_data_array_u32(address),
            7 => self.read_internal_register_longword(address),
            _ => unreachable!("u32 >> 29 is always 0-7"),
        }
    }

    fn cached_read_longword<Bus: BusInterface>(&mut self, address: u32, bus: &mut Bus) -> u32 {
        if let Some(value) = self.cache.read_u32(address) {
            return value;
        }

        if self.cache.should_replace_data() {
            let cache_line =
                bus.apply_read_cache_line(address & CACHE_LINE_MASK, self.data_ctx, self);
            self.cache.replace(address, cache_line)
        } else {
            bus.apply_read_longword(address & EXTERNAL_ADDRESS_MASK, self.data_ctx, self)
        }
    }

    fn write_byte<Bus: BusInterface>(&mut self, address: u32, value: u8, bus: &mut Bus) {
        bus.check_write_byte(address, value, self);

        match address >> 29 {
            0 => {
                bus.apply_write_byte(address & EXTERNAL_ADDRESS_MASK, value, self.data_ctx, self);
                self.cache.write_through_u8(address, value);
            }
            1 => bus.apply_write_byte(address & EXTERNAL_ADDRESS_MASK, value, self.data_ctx, self),
            2 => self.cache.associative_purge(address),
            3..=5 => {
                log::warn!(
                    "SH-2 {:?} invalid byte write: {address:08X} {value:02X}, ctx: {}",
                    self.name,
                    self.data_ctx
                );
            }
            6 => self.cache.write_data_array_u8(address, value),
            7 => self.write_internal_register_byte(address, value, bus),
            _ => unreachable!("u32 >> 29 is always 0-7"),
        }
    }

    fn write_word<Bus: BusInterface>(&mut self, address: u32, value: u16, bus: &mut Bus) {
        bus.check_write_word(address, value, self);

        match address >> 29 {
            0 => {
                bus.apply_write_word(address & EXTERNAL_ADDRESS_MASK, value, self.data_ctx, self);
                self.cache.write_through_u16(address, value);
            }
            1 => bus.apply_write_word(address & EXTERNAL_ADDRESS_MASK, value, self.data_ctx, self),
            2 => self.cache.associative_purge(address),
            3..=5 => {
                log::warn!(
                    "SH-2 {:?} invalid word write: {address:08X} {value:04X}, ctx: {}",
                    self.name,
                    self.data_ctx
                );
            }
            6 => self.cache.write_data_array_u16(address, value),
            7 => self.write_internal_register_word(address, value),
            _ => unreachable!("u32 >> 29 is always 0-7"),
        }
    }

    #[allow(clippy::match_same_arms)]
    fn write_longword<Bus: BusInterface>(&mut self, address: u32, value: u32, bus: &mut Bus) {
        bus.check_write_longword(address, value, self);

        match address >> 29 {
            0 => {
                bus.apply_write_longword(
                    address & EXTERNAL_ADDRESS_MASK,
                    value,
                    self.data_ctx,
                    self,
                );
                self.cache.write_through_u32(address, value);
            }
            1 => bus.apply_write_longword(
                address & EXTERNAL_ADDRESS_MASK,
                value,
                self.data_ctx,
                self,
            ),
            2 => self.cache.associative_purge(address),
            3 => self.cache.write_address_array(address, value),
            4 | 5 => {
                log::warn!(
                    "SH-2 {:?} invalid longword write: {address:08X} {value:08X}, ctx: {}",
                    self.name,
                    self.data_ctx
                );
            }
            6 => self.cache.write_data_array_u32(address, value),
            7 => self.write_internal_register_longword(address, value),
            _ => unreachable!("u32 >> 29 is always 0-7"),
        }
    }

    fn handle_exception<Bus: BusInterface>(
        &mut self,
        interrupt_level: Option<u8>,
        vector_number: u32,
        bus: &mut Bus,
    ) {
        if let Some(mut debug_view) = bus.debug_view()
            && let Some(interrupt_level) = interrupt_level
        {
            debug_view.check_interrupt(interrupt_level, self);
        }

        let mut sp = self.registers.gpr[SP].wrapping_sub(4);
        self.write_longword(sp, self.registers.sr.into(), bus);

        sp = sp.wrapping_sub(4);
        self.write_longword(sp, self.registers.pc, bus);

        self.registers.gpr[SP] = sp;
        if let Some(interrupt_level) = interrupt_level {
            self.registers.sr.interrupt_mask = interrupt_level;
        }

        let vector_addr = self.registers.vbr.wrapping_add(vector_number << 2);
        self.registers.pc = self.read_longword(vector_addr, bus);
        self.registers.next_pc = self.registers.pc.wrapping_add(2);
        self.registers.next_op_in_delay_slot = false;

        // Interrupt handling takes 10 cycles, plus memory access time, plus time to flush the pipeline
        // Memory access time is already accounted for, so arbitrarily say it will take 3 cycles to flush
        bus.increment_cycle_counter(13);

        log::debug!(
            "[{}] Handled interrupt of level {interrupt_level:?} with vector number {vector_number}, jumped to {:08X}",
            self.name,
            self.registers.pc
        );
    }

    fn update_internal_interrupt_level(&mut self) {
        self.sh7604.update_interrupt_level(&self.dmac, &self.watchdog_timer, &self.serial);
    }

    #[inline]
    #[must_use]
    pub fn pc(&self) -> u32 {
        self.registers.pc
    }

    pub fn set_pc(&mut self, pc: u32) {
        self.registers.pc = pc;
        self.registers.next_pc = pc.wrapping_add(2);
    }

    #[inline]
    #[must_use]
    pub fn registers(&self) -> &Sh2Registers {
        &self.registers
    }

    #[must_use]
    pub fn peek_cache(&self, address: u32) -> Option<u16> {
        self.cache.peek(address)
    }

    #[must_use]
    pub fn peek_data_array(&self, address: u32) -> u16 {
        self.cache.peek_data_array(address)
    }

    #[must_use]
    pub fn debug_cache_view(&mut self) -> impl DebugMemoryView {
        self.cache.debug_view()
    }
}

// QA host code belongs to the runtime, never to serialized or cloned guest state.
#[cfg(feature = "jit-runtime")]
thread_local! {
    static PREVIEW_EXECUTORS: std::cell::RefCell<(u64, rustc_hash::FxHashMap<usize, Box<crate::native::NativeExecutor>>)> =
        std::cell::RefCell::new((u64::MAX, rustc_hash::FxHashMap::default()));
}

#[cfg(all(test, feature = "jit-runtime"))]
mod runtime_tests {
    use super::*;
    use std::borrow::Borrow;

    #[test]
    fn recursive_cpu_registry_keeps_executor_storage_stable_between_slices() {
        PREVIEW_EXECUTORS.with_borrow_mut(|(_, registry)| {
            registry.clear();
            registry.insert(1, Default::default());
            let initial: *const crate::native::NativeExecutor =
                std::ptr::from_ref(registry.get(&1).unwrap().borrow());
            let executor = registry.remove(&1).unwrap();
            let removed: *const crate::native::NativeExecutor =
                std::ptr::from_ref(executor.borrow());
            // A second CPU may populate and resize the registry while the first
            // executor is running in a callback. Only its owner should move.
            for cpu in 2..8 {
                registry.insert(cpu, Default::default());
            }
            registry.insert(1, executor);
            let returned: *const crate::native::NativeExecutor =
                std::ptr::from_ref(registry.get(&1).unwrap().borrow());
            assert_eq!(initial, removed);
            assert_eq!(initial, returned);
            registry.clear();
        });
    }
}
