//! Reusable oracle pattern: clone CPU/bus, execute each backend, compare the entire
//! encoded guest state, memory, ordered bus transactions and independently counted cycles.
//! On non-ARM64 this exercises fallback only; ARM64 additionally requires native calls.
use super::NativeExecutor;
use crate::Sh2;
use crate::bus::{AccessContext, BusInterface, OpSize};
use crate::debug::DummySh2Debugger;
use std::cell::RefCell;

#[derive(Clone)]
struct RecordingBus {
    memory: Vec<u8>,
    events: RefCell<Vec<String>>,
    cycles: u64,
    reset: bool,
    irq: u8,
    stop_at: u64,
}

impl RecordingBus {
    fn new() -> Self {
        Self {
            memory: vec![0; 4096],
            events: RefCell::default(),
            cycles: 0,
            reset: false,
            irq: 0,
            stop_at: u64::MAX,
        }
    }
    fn event(&self, value: String) {
        self.events.borrow_mut().push(value);
    }
    fn put(&mut self, address: u32, bytes: &[u8]) {
        for (i, &value) in bytes.iter().enumerate() {
            self.memory[(address as usize + i) & 4095] = value;
        }
    }
    fn program(&mut self, address: u32, opcodes: &[u16]) {
        for (i, opcode) in opcodes.iter().enumerate() {
            self.put(address + 2 * i as u32, &opcode.to_be_bytes());
        }
    }
    fn value(&self, address: u32, bytes: usize) -> u32 {
        (0..bytes).fold(0, |v, i| (v << 8) | u32::from(self.memory[(address as usize + i) & 4095]))
    }
}

impl BusInterface for RecordingBus {
    type DebugView<'a> = DummySh2Debugger;
    fn read<const SIZE: u8>(&mut self, address: u32, ctx: AccessContext) -> u32 {
        let value = self.value(address, 1 << SIZE);
        self.event(format!("read {SIZE} {address:08X} {value:08X} {ctx:?}"));
        self.increment_cycle_counter(2);
        value
    }
    fn read_cache_line(&mut self, address: u32, ctx: AccessContext) -> [u16; 8] {
        self.event(format!("line {address:08X} {ctx:?}"));
        self.increment_cycle_counter(5);
        std::array::from_fn(|i| self.value(address + 2 * i as u32, 2) as u16)
    }
    fn write<const SIZE: u8>(&mut self, address: u32, value: u32, ctx: AccessContext) {
        self.event(format!("write {SIZE} {address:08X} {value:08X} {ctx:?}"));
        let bytes = value.to_be_bytes();
        self.put(address, &bytes[4 - (1 << SIZE)..]);
        self.increment_cycle_counter(3);
    }
    fn reset(&self) -> bool {
        self.event("reset?".into());
        self.reset
    }
    fn interrupt_level(&self) -> u8 {
        self.event("irq?".into());
        self.irq
    }
    fn dma_request_0(&self) -> bool {
        self.event("dreq0?".into());
        true
    }
    fn dma_request_1(&self) -> bool {
        self.event("dreq1?".into());
        true
    }
    fn acknowledge_dreq_1(&mut self) {
        self.event("ack1".into());
    }
    fn serial_rx(&mut self) -> Option<u8> {
        self.event("rx".into());
        None
    }
    fn serial_tx(&mut self, value: u8) {
        self.event(format!("tx {value}"));
    }
    fn increment_cycle_counter(&mut self, cycles: u64) {
        self.event(format!("cycles +{cycles}"));
        self.cycles += cycles;
    }
    fn should_stop_execution(&self) -> bool {
        self.event("stop?".into());
        self.cycles >= self.stop_at
    }
    crate::impl_sh2_opcode_table!(RecordingBus);
}

fn cpu() -> Sh2 {
    let mut cpu = Sh2::new("differential".into());
    cpu.reset_pending = false;
    cpu.set_pc(0x20000100);
    cpu.registers.gpr = std::array::from_fn(|i| {
        [0, 1, u32::MAX, 0x80000000, 0x7FFFFFFF, 0x55555555, 0xAAAAAAAA, 0x12345678][i & 7]
    });
    cpu.registers.gpr[15] = 0x20000F00;
    cpu.registers.sr.t = true;
    cpu.registers.sr.s = true;
    cpu.registers.sr.q = true;
    cpu.registers.sr.m = true;
    cpu
}

fn state(cpu: &Sh2) -> Vec<u8> {
    bincode::encode_to_vec(cpu, bincode::config::standard()).unwrap()
}

fn compare(
    cpu: &Sh2,
    bus: &RecordingBus,
    ticks: u64,
    native: &mut NativeExecutor,
) -> (Sh2, RecordingBus) {
    let (mut interpreted, mut candidate) = (cpu.clone(), cpu.clone());
    let (mut expected_bus, mut actual_bus) = (bus.clone(), bus.clone());
    interpreted.execute(ticks, &mut expected_bus);
    candidate.execute_with_native(ticks, &mut actual_bus, native);
    assert!(
        state(&candidate) == state(&interpreted),
        "full CPU state at {:08X}, opcode {:04X}; actual regs {:?}; expected regs {:?}",
        cpu.pc(),
        bus.value(cpu.pc(), 2),
        candidate.registers,
        interpreted.registers
    );
    assert_eq!(actual_bus.memory, expected_bus.memory, "memory");
    assert_eq!(actual_bus.events, expected_bus.events, "ordered bus transactions and contexts");
    assert_eq!(actual_bus.cycles, expected_bus.cycles, "cycles");
    (candidate, actual_bus)
}

fn require_native(executor: &NativeExecutor, expected: u64) {
    if cfg!(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"))) {
        assert_eq!(
            executor.native_instructions(),
            expected,
            "native execution must not silently fall back"
        );
    } else {
        assert_eq!(executor.native_instructions(), 0, "unsupported host must fall back");
    }
}

#[test]
fn differential_all_register_pairs_and_signed_immediates() {
    let mut native = NativeExecutor::default();
    let mut count = 0;
    let mut bus = RecordingBus::new();
    // All n/m alias combinations, including R15; deterministic edge values and flags.
    for base in [0x6003, 0x300C, 0x2009, 0x200A, 0x200B] {
        for n in 0..16 {
            for m in 0..16 {
                let opcode = base | (n << 8) | (m << 4);
                bus.program(0x100, &[opcode]);
                compare(&cpu(), &bus, 1, &mut native);
                count += 1;
            }
        }
    }
    for base in [0xE000, 0x7000] {
        for n in 0..16 {
            for immediate in 0..256 {
                bus.program(0x100, &[base | (n << 8) | immediate]);
                compare(&cpu(), &bus, 1, &mut native);
                count += 1;
            }
        }
    }
    bus.program(0x100, &[0x0009]);
    compare(&cpu(), &bus, 1, &mut native);
    require_native(&native, count + 1);
}

#[test]
fn differential_branch_delay_irq_stop_reset_and_fallback() {
    let mut native = NativeExecutor::default();
    let mut bus = RecordingBus::new();
    // BRA + delay ADD, memory write/read, ADDC flags, SLEEP, and illegal-op exception.
    bus.program(0x100, &[0xA001, 0x7101, 0xE17F, 0x2212, 0x6322, 0x312E, 0x001B]);
    let mut initial = cpu();
    initial.registers.gpr[2] = 0x20000800;
    let (first, bus) = compare(&initial, &bus, 1, &mut native);
    assert!(first.registers.next_op_in_delay_slot);
    let (next, bus) = compare(&first, &bus, 1, &mut native);
    assert!(!next.registers.next_op_in_delay_slot);
    let (next, mut bus) = compare(&next, &bus, 4, &mut native);
    bus.stop_at = bus.cycles + 1;
    compare(&next, &bus, 30, &mut native);
    bus.stop_at = u64::MAX;
    bus.irq = 12;
    let mut irq_cpu = first.clone();
    irq_cpu.registers.sr.interrupt_mask = 0;
    bus.put((64 + 6) * 4, &0x20000200u32.to_be_bytes());
    let (handled, _) = compare(&irq_cpu, &bus, 3, &mut native);
    assert_eq!(handled.pc(), 0x20000200);
    bus.irq = 0;
    let mut illegal = cpu();
    illegal.set_pc(0x20000300);
    bus.program(0x300, &[0xFFFF]);
    bus.put(16, &0x20000400u32.to_be_bytes());
    compare(&illegal, &bus, 1, &mut native);
    bus.reset = true;
    let (pending, mut bus) = compare(&next, &bus, 3, &mut native);
    bus.reset = false;
    bus.put(0, &0x20000100u32.to_be_bytes());
    bus.put(4, &0x20000F00u32.to_be_bytes());
    compare(&pending, &bus, 3, &mut native);
    compare(&pending, &bus, 0, &mut native);
    // One native ADD in each of normal and pre-IRQ delay slots; other opcodes fall back.
    require_native(&native, 8);
}

#[test]
fn differential_dma_cache_changed_fetch_and_state_restore() {
    let mut native = NativeExecutor::default();
    let mut initial = cpu();
    let mut bus = RecordingBus::new();
    bus.program(0x100, &[0xE101, 0x7101, 0x0009]);
    initial.dmac.operation.dma_master_enabled = true;
    let channel = &mut initial.dmac.channels[1];
    channel.source_address = 0x800;
    channel.destination_address = 0x900;
    channel.transfer_count = 2;
    channel.control.dma_enabled = true;
    channel.control.source_address_mode = crate::DmaAddressMode::AutoIncrement;
    channel.control.destination_address_mode = crate::DmaAddressMode::AutoIncrement;
    bus.put(0x800, &[0x12, 0x34]);
    let (mut next, mut bus) = compare(&initial, &bus, 2, &mut native);
    assert_eq!(&bus.memory[0x900..0x902], &[0x12, 0x34]);
    next.set_pc(0x20000100);
    bus.program(0x100, &[0xE1FF]);
    let (mut next, bus) = compare(&next, &bus, 1, &mut native);
    assert_eq!(next.registers.gpr[1], u32::MAX, "new fetched opcode selects new body");
    next.set_pc(0x100);
    next.cache.write_control(1);
    let (mut cached, mut bus) = compare(&next, &bus, 1, &mut native);
    cached.set_pc(0x100);
    bus.program(0x100, &[0xE144]);
    let (cached, bus) = compare(&cached, &bus, 1, &mut native);
    assert_eq!(cached.registers.gpr[1], u32::MAX, "cached fetched opcode wins over bus memory");
    let encoded = state(&cached);
    let (mut restored, used): (Sh2, _) =
        bincode::decode_from_slice(&encoded, bincode::config::standard()).unwrap();
    assert_eq!(used, encoded.len());
    restored.set_pc(0x20000100);
    compare(&restored, &bus, 1, &mut native);
    // A stale guard preserves its real fetch and runs the changed legal opcode
    // natively after leaving the old block. Restored state can learn fresh code.
    require_native(&native, 6);
    let mut disabled = NativeExecutor::default();
    disabled.disable();
    compare(&restored, &bus, 2, &mut disabled);
    assert_eq!(disabled.native_instructions(), 0);
}

#[test]
fn recording_bus_accounts_for_access_sizes() {
    let mut bus = RecordingBus::new();
    bus.write::<{ OpSize::LONGWORD }>(0x800, 0x12345678, AccessContext::Dma { channel: 0 });
    assert_eq!(bus.read::<{ OpSize::WORD }>(0x802, AccessContext::Fetch), 0x5678);
    assert_eq!(bus.cycles, 5);
}

#[test]
fn differential_guarded_blocks_budget_stop_and_changed_opcodes() {
    let mut native = NativeExecutor::default();
    let mut bus = RecordingBus::new();
    let program = [0xE101, 0x7101, 0x6213, 0x312C, 0x212A, 0x0009, 0x7102, 0x7103];
    bus.program(0x100, &program);
    // Learn from executed fetches, then enter the same block with every possible budget.
    compare(&cpu(), &bus, 8, &mut native);
    for budget in 1..=8 {
        compare(&cpu(), &bus, budget, &mut native);
    }
    for stop in 1..=24 {
        bus.stop_at = stop;
        compare(&cpu(), &bus, 8, &mut native);
    }
    bus.stop_at = u64::MAX;
    // Invalidate an interior guard, including an opcode which requires the interpreter.
    for replacement in [0xE1FF, 0x312E, 0xA001, 0xFFFF] {
        bus.program(0x104, &[replacement]);
        compare(&cpu(), &bus, 8, &mut native);
        bus.program(0x100, &program);
    }
    if cfg!(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"))) {
        assert!(native.native_blocks() > 0, "must execute generated multi-instruction blocks");
        assert!(native.guard_exits() > 0, "must exercise a real guard mismatch");
        assert!(native.block_instructions() > 8, "must execute several native bodies per block");
        assert!(native.budget_exits() > 0);
        assert!(native.stop_exits() > 0);
        assert!(native.interpreted_instructions() > 0);
    }
}

#[cfg(all(target_arch = "aarch64", target_endian = "little", any(target_os = "android", target_os = "linux")))]
#[test]
fn conditional_branch_path_changes_keep_valid_learned_blocks() {
    // BT +4 was not taken while learning the contiguous trace. Taking it later
    // changes control flow, but none of the learned instruction bytes changed.
    let program = [0x8904, 0x0009, 0xE101, 0x7101, 0x0009, 0x0009, 0xE207];
    let mut initial = cpu();
    initial.trace_log_enabled = false;
    initial.registers.sr.t = false;
    let mut native = NativeExecutor::default();
    let mut bus = RecordingBus::new();
    bus.program(0x100, &program);
    compare(&initial, &bus, 4, &mut native);
    let entry = initial.pc();
    let pointer = unsafe { native.blocks[&entry].entry_pointer() };
    initial.registers.sr.t = true;
    compare(&initial, &bus, 2, &mut native);
    assert_eq!(unsafe { native.blocks[&entry].entry_pointer() }, pointer);

    let mut direct = DirectBus::new();
    direct.program(&program);
    let mut native = NativeExecutor::default();
    initial.registers.sr.t = false;
    compare_direct(&initial, &direct, 4, &mut native);
    let pointer = unsafe { native.fast_blocks[&entry].code.entry_pointer() };
    initial.registers.sr.t = true;
    compare_direct(&initial, &direct, 2, &mut native);
    assert_eq!(unsafe { native.fast_blocks[&entry].code.entry_pointer() }, pointer);
}

#[test]
fn differential_warm_blocks_preserve_cache_dma_irq_and_restored_state() {
    let mut native = NativeExecutor::default();
    let mut initial = cpu();
    initial.set_pc(0x100);
    initial.cache.write_control(1);
    let mut bus = RecordingBus::new();
    bus.program(0x100, &[0xE101, 0x7101, 0x6213, 0x312C, 0x212A, 0x0009, 0x7102, 0x7103]);
    // First real execution populates guest cache and learns the native block.
    let (mut warm, mut warm_bus) = compare(&initial, &bus, 8, &mut native);
    warm.set_pc(0x100);
    warm_bus.events.borrow_mut().clear();
    // Underlying memory changes must remain invisible until the guest cache is purged.
    warm_bus.program(0x100, &[0xE17F, 0x7107]);
    let (cached, _) = compare(&warm, &warm_bus, 8, &mut native);
    assert_eq!(cached.registers.gpr[1], 11);
    // Serialize only guest state, then reuse the existing host code with that restored state.
    let encoded = state(&warm);
    let (restored, _): (Sh2, _) =
        bincode::decode_from_slice(&encoded, bincode::config::standard()).unwrap();
    compare(&restored, &warm_bus, 5, &mut native);
    warm.cache.purge_all();
    compare(&warm, &warm_bus, 8, &mut native);

    let mut irq_cpu = warm.clone();
    irq_cpu.registers.sr.interrupt_mask = 0;
    warm_bus.irq = 12;
    warm_bus.put((64 + 6) * 4, &0x20000300u32.to_be_bytes());
    let before = native.native_instructions();
    compare(&irq_cpu, &warm_bus, 8, &mut native);
    assert_eq!(native.native_instructions(), before, "IRQ exits before entering learned block");
    warm_bus.irq = 0;
    warm_bus.reset = true;
    compare(&warm, &warm_bus, 8, &mut native);
    assert_eq!(native.native_instructions(), before, "reset exits before entering learned block");
    warm_bus.reset = false;

    // DMA runs before instruction entry and may overwrite a previously learned opcode.
    let mut dma_cpu = cpu();
    bus.program(0x100, &[0xE101, 0x7101, 0x6213, 0x312C]);
    compare(&dma_cpu, &bus, 4, &mut native);
    dma_cpu.dmac.operation.dma_master_enabled = true;
    let channel = &mut dma_cpu.dmac.channels[1];
    channel.source_address = 0x800;
    channel.destination_address = 0x100;
    channel.transfer_count = 2;
    channel.control.dma_enabled = true;
    channel.control.source_address_mode = crate::DmaAddressMode::AutoIncrement;
    channel.control.destination_address_mode = crate::DmaAddressMode::AutoIncrement;
    bus.put(0x800, &0xE1FFu16.to_be_bytes());
    compare(&dma_cpu, &bus, 4, &mut native);
    if cfg!(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"))) {
        assert!(native.block_instructions() >= 13);
        assert!(native.guard_exits() >= 2);
    }
}

// This bus deliberately has no logging, allocation, synchronization or formatted events.
// It measures dispatch/fetch/body overhead, not game-level speed or peripheral timing.
struct TimingBus {
    cycles: u64,
    limit: u64,
    words: [u16; 2048],
}
impl BusInterface for TimingBus {
    type DebugView<'a> = DummySh2Debugger;
    fn read<const SIZE: u8>(&mut self, address: u32, _: AccessContext) -> u32 {
        self.cycles += 2;
        if SIZE == OpSize::WORD {
            [0x7101, 0x6213, 0x312C, 0x212A, 0x222B, 0x6323, 0x2339, 0x0009]
                [(address as usize >> 1) & 7]
        } else {
            0
        }
    }
    fn read_cache_line(&mut self, _: u32, _: AccessContext) -> [u16; 8] {
        unreachable!()
    }
    fn write<const SIZE: u8>(&mut self, _: u32, _: u32, _: AccessContext) {
        unreachable!()
    }
    fn reset(&self) -> bool {
        false
    }
    fn interrupt_level(&self) -> u8 {
        0
    }
    fn dma_request_0(&self) -> bool {
        false
    }
    fn dma_request_1(&self) -> bool {
        false
    }
    fn acknowledge_dreq_1(&mut self) {}
    fn serial_rx(&mut self) -> Option<u8> {
        None
    }
    fn serial_tx(&mut self, _: u8) {}
    fn increment_cycle_counter(&mut self, cycles: u64) {
        self.cycles += cycles;
    }
    fn should_stop_execution(&self) -> bool {
        self.cycles >= self.limit
    }
    unsafe fn native_bus_view(&mut self) -> Option<crate::bus::NativeBusView> {
        Some(crate::bus::NativeBusView {
            cycle_counter: &mut self.cycles,
            cycle_limit: &self.limit,
            fetch_start: 0x20000000,
            fetch_end: 0x20001000,
            fetch_words: self.words.as_ptr(),
            fetch_cycles: 2,
        })
    }
    crate::impl_sh2_opcode_table!(TimingBus);
}

#[test]
#[ignore = "release-only synthetic A/B benchmark; run explicitly with --ignored --nocapture"]
fn benchmark_native_dispatch_without_bus_logging() {
    use std::{hint::black_box, time::Instant};
    const ITERATIONS: u64 = 100_000;
    let mut reference = None;
    for mode in 0..3 {
        let mut samples = Vec::new();
        let mut totals = (0, 0, 0, 0);
        for _ in 0..5 {
            let mut cpu = cpu();
            cpu.trace_log_enabled = false;
            let mut bus = TimingBus {
                cycles: 0,
                limit: u64::MAX,
                words: std::array::from_fn(|i| {
                    [0x7101, 0x6213, 0x312C, 0x212A, 0x222B, 0x6323, 0x2339, 0x0009][i & 7]
                }),
            };
            let mut native = NativeExecutor::default();
            if mode == 1 {
                native.disable_blocks();
            }
            let start = Instant::now();
            for _ in 0..ITERATIONS {
                cpu.set_pc(0x20000100);
                if mode == 0 {
                    cpu.execute(16, black_box(&mut bus));
                } else {
                    cpu.execute_with_native(16, black_box(&mut bus), black_box(&mut native));
                }
            }
            samples.push(start.elapsed().as_secs_f64() * 1e9 / (ITERATIONS * 16) as f64);
            let result = (state(&cpu), bus.cycles);
            if let Some(expected) = &reference {
                assert_eq!(&result, expected);
            } else {
                reference = Some(result);
            }
            totals = (
                native.native_instructions(),
                native.block_instructions(),
                native.interpreted_instructions(),
                native.fast_instructions(),
            );
        }
        samples.sort_by(f64::total_cmp);
        println!(
            "SH2 mode={} median_ns/instruction={:.3} samples={samples:?} native={} block={} fallback={} fast={}",
            ["interpreter", "native-leaf", "guarded-block"][mode],
            samples[2],
            totals.0,
            totals.1,
            totals.2,
            totals.3
        );
        if cfg!(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux")))
            && mode != 0
        {
            assert_eq!(totals.0, ITERATIONS * 16);
            if mode == 2 {
                assert_eq!(totals.1, (ITERATIONS - 1) * 16);
            }
        }
    }
}

#[test]
fn native_register_flags_shifts_all_aliases_and_edges() {
    let mut native = NativeExecutor::default();
    let mut count = 0;
    let mut bus = RecordingBus::new();
    for base in [
        0x3008, 0x6007, 0x600B, 0x600C, 0x600D, 0x600E, 0x600F, 0x6008, 0x6009, 0x200D, 0x3000,
        0x3002, 0x3003, 0x3006, 0x3007, 0x2008, 0x300E, 0x300F, 0x300A, 0x300B, 0x600A,
    ] {
        for n in 0..16 {
            for m in 0..16 {
                for t in [false, true] {
                    let mut cpu = cpu();
                    cpu.registers.sr.t = t;
                    bus.program(0x100, &[base | (n << 8) | (m << 4)]);
                    compare(&cpu, &bus, 1, &mut native);
                    count += 1;
                }
            }
        }
    }
    for base in [
        0x4008, 0x4018, 0x4028, 0x4009, 0x4019, 0x4029, 0x4000, 0x4020, 0x4001, 0x4021, 0x4004,
        0x4005, 0x4024, 0x4025, 0x4010, 0x4011, 0x4015, 0x0029,
    ] {
        for n in 0..16 {
            for value in [0, 1, 2, 0x80000000, 0x7FFFFFFF, u32::MAX, 0xA55AA55A] {
                for t in [false, true] {
                    let mut cpu = cpu();
                    cpu.registers.gpr[n as usize] = value;
                    cpu.registers.sr.t = t;
                    bus.program(0x100, &[base | (n << 8)]);
                    compare(&cpu, &bus, 1, &mut native);
                    count += 1;
                }
            }
        }
    }
    for base in [0xC900, 0xCA00, 0xCB00, 0xC800, 0x8800] {
        for imm in 0..256 {
            bus.program(0x100, &[base | imm]);
            compare(&cpu(), &bus, 1, &mut native);
            count += 1;
        }
    }
    for op in [0x0008, 0x0018] {
        bus.program(0x100, &[op]);
        compare(&cpu(), &bus, 1, &mut native);
        count += 1;
    }
    require_native(&native, count);
}
#[test]
fn native_memory_modes_sizes_aliasing_and_bus_order() {
    let mut native = NativeExecutor::default();
    let mut count = 0;
    for base in [
        0x6000, 0x6001, 0x6002, 0x6004, 0x6005, 0x6006, 0x2000, 0x2001, 0x2002, 0x2004, 0x2005,
        0x2006, 0x0004, 0x0005, 0x0006, 0x000C, 0x000D, 0x000E, 0x1000, 0x5000,
    ] {
        for n in 0..16 {
            for m in 0..16 {
                let mut cpu = cpu();
                cpu.registers.gpr = std::array::from_fn(|r| 0x20000800 + r as u32 * 4);
                // Indexed operations deliberately include aliasing with R0.
                let mut bus = RecordingBus::new();
                bus.program(0x100, &[base | (n << 8) | (m << 4)]);
                bus.put(0x800, &[0x80, 0xFF, 0x12, 0x34, 0xAA, 0x55, 0xFE, 0xDC]);
                compare(&cpu, &bus, 1, &mut native);
                count += 1;
            }
        }
    }
    for base in [0x9000, 0xD000] {
        for n in 0..16 {
            for disp in [0, 1, 2, 127, 255] {
                let mut bus = RecordingBus::new();
                bus.program(0x100, &[base | (n << 8) | disp]);
                compare(&cpu(), &bus, 1, &mut native);
                count += 1;
            }
        }
    }
    require_native(&native, count);
}

#[test]
fn native_branch_targets_conditions_delay_and_link() {
    let mut native = NativeExecutor::default();
    let mut count = 0;
    for base in [0x8900, 0x8B00, 0x8D00, 0x8F00, 0xA000, 0xB000] {
        for disp in 0..if base >= 0xA000 { 4096 } else { 256 } {
            for t in [false, true] {
                let mut cpu = cpu();
                cpu.registers.sr.t = t;
                let mut bus = RecordingBus::new();
                bus.program(0x100, &[base | disp, 0x7101]);
                let (next, bus) = compare(&cpu, &bus, 1, &mut native);
                count += 1;
                // Exercise the scheduler's real delay slot, including interrupt checks.
                if next.registers.next_op_in_delay_slot {
                    compare(&next, &bus, 1, &mut native);
                    count += 1;
                }
            }
        }
    }
    for base in [0x402B, 0x400B, 0x0023, 0x0003] {
        for n in 0..16 {
            let mut cpu = cpu();
            cpu.registers.gpr[n as usize] = 0x20000104;
            let mut bus = RecordingBus::new();
            bus.program(0x100, &[base | (n << 8), 0x7101]);
            let (next, bus) = compare(&cpu, &bus, 1, &mut native);
            count += 1;
            compare(&next, &bus, 1, &mut native);
            count += 1;
        }
    }
    let mut bus = RecordingBus::new();
    bus.program(0x100, &[0x000B, 0x7101]);
    compare(&cpu(), &bus, 1, &mut native);
    count += 1;
    require_native(&native, count);
}
#[test]
fn warmed_native_blocks_include_memory_flags_and_branches() {
    let mut native = NativeExecutor::default();
    let mut initial = cpu();
    initial.registers.gpr[2] = 0x20000800;
    let mut bus = RecordingBus::new();
    bus.program(0x100, &[0xE17F, 0x2212, 0x6322, 0x313E, 0x4321, 0x0009, 0xA001, 0x7101]);
    compare(&initial, &bus, 8, &mut native);
    compare(&initial, &bus, 8, &mut native);
    if cfg!(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"))) {
        assert!(native.block_instructions() >= 6);
    }
}

fn coverage_inventory() -> (usize, usize) {
    let table = RecordingBus::opcode_table();
    let illegal = table.decode(0xFFFF);
    let mut counts = std::collections::BTreeMap::<&str, usize>::new();
    let mut native = 0;
    let mut legal = 0;
    let mut csv = String::from("opcode,category,instruction\n");
    for opcode in 0..=u16::MAX {
        // The compressed interpreter decoder resolves group 8 inside its callback.
        let reserved = if opcode >> 12 == 8 {
            !matches!((opcode >> 8) & 15, 0 | 1 | 4 | 5 | 8 | 9 | 11 | 13 | 15)
        } else {
            std::ptr::fn_addr_eq(table.decode(opcode), illegal)
        };
        let emitted = super::emit(opcode).is_some();
        assert!(!(reserved && emitted), "native emitter accepted reserved {opcode:04X}");
        let category = if reserved {
            "reserved"
        } else if emitted {
            native += 1;
            legal += 1;
            "native"
        } else {
            legal += 1;
            "fallback"
        };
        *counts.entry(category).or_default() += 1;
        let mut dis = crate::DisassembledInstruction::default();
        crate::disassemble_into(0, opcode, &mut dis);
        csv.push_str(&format!("{opcode:04X},{category},\"{}\"\n", dis.text));
    }
    assert_eq!(counts.values().sum::<usize>(), 65536);
    println!("SH2 coverage {counts:?}; native={native}/{legal} legal interpreter decode encodings");
    if let Ok(dir) = std::env::var("EMUCORE_JIT_INVENTORY_DIR") {
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(std::path::Path::new(&dir).join("sh2-native-coverage.csv"), csv).unwrap();
    }
    (native, legal)
}
#[test]
fn all_opcode_coverage_inventory() {
    coverage_inventory();
}
#[test]
#[ignore = "extended ARM64 acceptance: all legal encodings across four randomized states and cached/uncached buses; invoke explicitly on device"]
fn full_native_isa_coverage_acceptance_gate() {
    assert!(
        cfg!(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"))),
        "this execution acceptance gate requires an ARM64 host"
    );
    let (native, legal) = coverage_inventory();
    assert_eq!(native, legal, "full SH2 native ISA coverage is still incomplete");
    for seed in [0, 0x12345678, 0x80000000, 0xFFFFFFFF] {
        for cache in [false, true] {
            execute_legal_opcode_sweep(seed, cache);
        }
    }
}

#[test]
fn native_multiply_divide_special_registers_and_sr_bits() {
    let mut native = NativeExecutor::default();
    let mut count = 0;
    let mut bus = RecordingBus::new();
    for base in [0x0007, 0x200E, 0x200F, 0x3005, 0x300D, 0x200C, 0x2007, 0x3004] {
        for n in 0..16 {
            for m in 0..16 {
                for sr in 0..16 {
                    let mut cpu = cpu();
                    cpu.registers.sr.t = sr & 1 != 0;
                    cpu.registers.sr.q = sr & 2 != 0;
                    cpu.registers.sr.m = sr & 4 != 0;
                    cpu.registers.sr.s = sr & 8 != 0;
                    bus.program(0x100, &[base | (n << 8) | (m << 4)]);
                    compare(&cpu, &bus, 1, &mut native);
                    count += 1;
                }
            }
        }
    }
    for base in [
        0x0002, 0x0012, 0x0022, 0x000A, 0x001A, 0x002A, 0x400E, 0x401E, 0x402E, 0x400A, 0x401A,
        0x402A, 0x0019, 0x0028, 0x0008, 0x0018, 0x0009, 0x001B,
    ] {
        for n in 0..16 {
            bus.program(0x100, &[base | (n << 8)]);
            compare(&cpu(), &bus, 1, &mut native);
            count += 1;
        }
    }
    for base in [0x8000, 0x8100, 0x8400, 0x8500] {
        for m in 0..16 {
            for disp in 0..16 {
                let mut initial = cpu();
                initial.registers.gpr[m as usize] = 0x20000800;
                bus.program(0x100, &[base | (m << 4) | disp]);
                compare(&initial, &bus, 1, &mut native);
                count += 1;
            }
        }
    }
    for base in [0xC000, 0xC100, 0xC200, 0xC400, 0xC500, 0xC600, 0xC700] {
        for disp in 0..256 {
            let mut initial = cpu();
            initial.registers.gbr = 0x20000800;
            bus.program(0x100, &[base | disp]);
            compare(&initial, &bus, 1, &mut native);
            count += 1;
        }
    }
    require_native(&native, count);
}

#[test]
fn native_complex_memory_sr_traps_and_saturating_mac() {
    let mut native = NativeExecutor::default();
    let mut count = 0;
    for base in [
        0x4002, 0x4012, 0x4022, 0x4003, 0x4013, 0x4023, 0x4006, 0x4016, 0x4026, 0x4007, 0x4017,
        0x4027, 0x401B,
    ] {
        for n in 0..16 {
            for cache in [false, true] {
                let mut initial = cpu();
                initial.registers.gpr[n as usize] = if cache { 0x800 } else { 0x20000800 };
                if cache {
                    initial.cache.write_control(1);
                }
                let mut bus = RecordingBus::new();
                bus.program(0x100, &[base | (n << 8)]);
                bus.put(0x800, &0xFEDCBA98u32.to_be_bytes());
                compare(&initial, &bus, 1, &mut native);
                count += 1;
            }
        }
    }
    for base in [0xCC00, 0xCD00, 0xCE00, 0xCF00] {
        for imm in 0..256 {
            let mut initial = cpu();
            initial.registers.gbr = 0x20000800;
            initial.registers.gpr[0] = 0;
            let mut bus = RecordingBus::new();
            bus.program(0x100, &[base | imm]);
            bus.put(0x800, &[imm as u8]);
            compare(&initial, &bus, 1, &mut native);
            count += 1;
        }
    }
    for vector in 0..256 {
        let mut bus = RecordingBus::new();
        bus.program(0x100, &[0xC300 | vector]);
        compare(&cpu(), &bus, 1, &mut native);
        count += 1;
    }
    for alias in 0..16 {
        let mut bus = RecordingBus::new();
        bus.program(0x100, &[0x002B | (alias << 8)]);
        bus.put(0xF00, &0x20000200u32.to_be_bytes());
        bus.put(0xF04, &0x000003F3u32.to_be_bytes());
        compare(&cpu(), &bus, 1, &mut native);
        count += 1;
    }
    for base in [0x000F, 0x400F] {
        for n in 0..16 {
            for m in 0..16 {
                for s in [false, true] {
                    for acc in
                        [0, 0x7FFFFFFF, 0x80000000, 0x7FFFFFFFFFFF, 0xFFFF800000000000, u64::MAX]
                    {
                        let mut initial = cpu();
                        initial.registers.gpr = std::array::from_fn(|r| 0x20000800 + r as u32 * 4);
                        initial.registers.sr.s = s;
                        initial.registers.mach = (acc >> 32) as u32;
                        initial.registers.macl = acc as u32;
                        let mut bus = RecordingBus::new();
                        bus.program(0x100, &[base | (n << 8) | (m << 4)]);
                        for a in (0x800..0x850).step_by(4) {
                            bus.put(a, &[0x80, 0x00, 0xFF, 0xFE]);
                        }
                        compare(&initial, &bus, 1, &mut native);
                        count += 1;
                    }
                }
            }
        }
    }
    require_native(&native, count);
}

fn execute_legal_opcode_sweep(seed: u32, cached: bool) {
    let mut native = NativeExecutor::default();
    let mut count = 0;
    let table = RecordingBus::opcode_table();
    let illegal = table.decode(0xFFFF);
    for opcode in 0..=u16::MAX {
        let reserved = if opcode >> 12 == 8 {
            !matches!((opcode >> 8) & 15, 0 | 1 | 4 | 5 | 8 | 9 | 11 | 13 | 15)
        } else {
            std::ptr::fn_addr_eq(table.decode(opcode), illegal)
        };
        if reserved {
            continue;
        }
        let mut initial = cpu();
        initial.trace_log_enabled = false;
        if cached {
            initial.set_pc(0x100);
            initial.cache.write_control(1);
        }
        let mut random = seed ^ u32::from(opcode).wrapping_mul(0x9E3779B9);
        initial.registers.gpr = std::array::from_fn(|r| {
            random ^= random << 13;
            random ^= random >> 17;
            random ^= random << 5;
            (if cached { 0 } else { 0x20000000 })
                | 0x800
                | ((random.wrapping_add(r as u32 * 4)) & 0x3FC)
        });
        initial.registers.sr = crate::registers::StatusRegister::from(random);
        initial.registers.mach = random.rotate_left(7);
        initial.registers.macl = random.rotate_left(19);
        initial.registers.gbr = if cached { 0x800 } else { 0x20000800 };
        initial.registers.vbr = 0;
        let mut bus = RecordingBus::new();
        for (i, value) in bus.memory.iter_mut().enumerate() {
            *value = (i as u8).wrapping_mul(13).wrapping_add(seed as u8);
        }
        bus.program(0x100, &[opcode]);
        compare(&initial, &bus, 1, &mut native);
        count += 1;
    }
    assert_eq!(count, 53872, "all legal interpreter decode encodings must be exercised");
    require_native(&native, count);
    println!(
        "SH2 complete native execution sweep seed={seed:08X} cached={cached}: {count} legal encodings"
    );
}
#[test]
fn every_legal_opcode_encoding_executes_against_full_state_bus_and_cycle_oracle() {
    execute_legal_opcode_sweep(0xC0FFEE11, false);
}

#[test]
#[ignore = "extended ARM64 block acceptance: all legal encodings execute as inlined bodies after a warmed NOP; invoke explicitly on device"]
fn every_legal_opcode_executes_inside_warmed_native_blocks() {
    assert!(
        cfg!(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"))),
        "requires ARM64 native execution"
    );
    let table = RecordingBus::opcode_table();
    let illegal = table.decode(0xFFFF);
    let mut total = 0;
    for cached in [false, true] {
        for opcode in 0..=u16::MAX {
            let reserved = if opcode >> 12 == 8 {
                !matches!((opcode >> 8) & 15, 0 | 1 | 4 | 5 | 8 | 9 | 11 | 13 | 15)
            } else {
                std::ptr::fn_addr_eq(table.decode(opcode), illegal)
            };
            if reserved {
                continue;
            }
            let mut initial = cpu();
            initial.trace_log_enabled = false;
            let bank = if cached { 0 } else { 0x20000000 };
            initial.registers.gpr = std::array::from_fn(|r| bank | 0x800 | (r as u32 * 4));
            initial.registers.gbr = bank | 0x800;
            initial.registers.sr =
                crate::registers::StatusRegister::from(u32::from(opcode).rotate_left(7));
            initial.registers.mach = 0x7FFF;
            initial.registers.macl = 0xFFFFFFFF;
            if cached {
                initial.set_pc(0x100);
                initial.cache.write_control(1);
            }
            let mut bus = RecordingBus::new();
            for (i, v) in bus.memory.iter_mut().enumerate() {
                *v = (i as u8).wrapping_mul(17);
            }
            bus.program(0x100, &[0x0009, opcode]);
            let mut native = NativeExecutor::default();
            compare(&initial, &bus, 2, &mut native);
            native.finish_trace();
            compare(&initial, &bus, 2, &mut native);
            require_native(&native, 4);
            assert_eq!(
                native.block_instructions(),
                2,
                "opcode {opcode:04X} must execute as an inlined body"
            );
            total += 1;
        }
    }
    assert_eq!(total, 107744);
    println!(
        "SH2 complete warmed-block sweep: {total} legal encodings across cached/uncached buses"
    );
}

// This bus opts in only because every granted operation has exactly the documented
// scalar effects: stable native-endian RAM and cycle/limit values, no observers/MMIO.
#[derive(Clone)]
struct DirectBus {
    words: [u16; 2048],
    cycles: u64,
    limit: u64,
    direct: bool,
}
impl DirectBus {
    fn new() -> Self {
        Self {
            words: std::array::from_fn(|i| i as u16 * 13),
            cycles: 0,
            limit: u64::MAX,
            direct: true,
        }
    }
    fn program(&mut self, ops: &[u16]) {
        self.words[0x80..0x80 + ops.len()].copy_from_slice(ops);
    }
}
impl BusInterface for DirectBus {
    type DebugView<'a> = DummySh2Debugger;
    fn read<const S: u8>(&mut self, a: u32, _: AccessContext) -> u32 {
        self.cycles += 2;
        let i = ((a >> 1) & 2047) as usize;
        match S {
            0 => u32::from(self.words[i].to_be_bytes()[(a & 1) as usize]),
            1 => u32::from(self.words[i]),
            _ => {
                let i = i & !1;
                (u32::from(self.words[i]) << 16) | u32::from(self.words[i + 1])
            }
        }
    }
    fn read_cache_line(&mut self, a: u32, _: AccessContext) -> [u16; 8] {
        self.cycles += 5;
        let i = ((a >> 1) & 2040) as usize;
        std::array::from_fn(|n| self.words[i + n])
    }
    fn write<const S: u8>(&mut self, a: u32, v: u32, _: AccessContext) {
        self.cycles += 3;
        let i = ((a >> 1) & 2047) as usize;
        match S {
            0 => {
                let mut b = self.words[i].to_be_bytes();
                b[(a & 1) as usize] = v as u8;
                self.words[i] = u16::from_be_bytes(b);
            }
            1 => self.words[i] = v as u16,
            _ => {
                let i = i & !1;
                self.words[i] = (v >> 16) as u16;
                self.words[i + 1] = v as u16;
            }
        }
    }
    fn reset(&self) -> bool {
        false
    }
    fn interrupt_level(&self) -> u8 {
        0
    }
    fn dma_request_0(&self) -> bool {
        false
    }
    fn dma_request_1(&self) -> bool {
        false
    }
    fn acknowledge_dreq_1(&mut self) {}
    fn serial_rx(&mut self) -> Option<u8> {
        None
    }
    fn serial_tx(&mut self, _: u8) {}
    fn increment_cycle_counter(&mut self, n: u64) {
        self.cycles += n;
    }
    fn should_stop_execution(&self) -> bool {
        self.cycles >= self.limit
    }
    unsafe fn native_bus_view(&mut self) -> Option<crate::bus::NativeBusView> {
        self.direct.then(|| crate::bus::NativeBusView {
            cycle_counter: &mut self.cycles,
            cycle_limit: &self.limit,
            fetch_start: 0x20000000,
            fetch_end: 0x20001000,
            fetch_words: self.words.as_ptr(),
            fetch_cycles: 2,
        })
    }
    crate::impl_sh2_opcode_table!(DirectBus);
}
fn compare_direct(
    initial: &Sh2,
    bus: &DirectBus,
    ticks: u64,
    native: &mut NativeExecutor,
) -> (Sh2, DirectBus) {
    let (mut oracle, mut actual) = (initial.clone(), initial.clone());
    let (mut expected, mut bus) = (bus.clone(), bus.clone());
    oracle.execute(ticks, &mut expected);
    actual.execute_with_native(ticks, &mut bus, native);
    assert!(
        state(&actual) == state(&oracle),
        "full direct-view CPU state at {:08X}: actual {:?}, expected {:?}",
        initial.pc(),
        actual.registers,
        oracle.registers
    );
    assert_eq!(bus.words, expected.words, "direct-view memory");
    assert_eq!(bus.cycles, expected.cycles, "direct-view cycles");
    (actual, bus)
}
#[test]
fn scoped_fast_fetch_guards_cache_lru_budget_stop_and_changed_code() {
    let mut native = NativeExecutor::default();
    let mut bus = DirectBus::new();
    bus.program(&[0xE17F, 0x7101, 0x2212, 0x6322, 0x313E, 0x4321, 0x0009, 0x0009]);
    for cached in [false, true] {
        let mut initial = cpu();
        initial.registers.gpr[2] = if cached { 0x800 } else { 0x20000800 };
        initial.trace_log_enabled = false;
        if cached {
            initial.set_pc(0x100);
            initial.cache.write_control(1);
        }
        let (mut warm, bus) = compare_direct(&initial, &bus, 8, &mut native);
        native.finish_trace();
        warm.set_pc(initial.pc());
        for ticks in 1..=8 {
            compare_direct(&warm, &bus, ticks, &mut native);
        }
        for limit in bus.cycles + 1..=bus.cycles + 24 {
            let mut stopped = bus.clone();
            stopped.limit = limit;
            compare_direct(&warm, &stopped, 8, &mut native);
        }
        let mut changed = bus.clone();
        changed.words[0x82] = 0xE1FF;
        compare_direct(&warm, &changed, 8, &mut native);
    }
    if cfg!(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"))) {
        assert!(native.fast_instructions() > 32, "must enter generated fast fetch blocks");
    }
}

#[test]
fn uncached_p0_fast_fetch_misses_use_warmed_guarded_native_blocks() {
    let mut initial = cpu();
    initial.trace_log_enabled = false;
    initial.set_pc(0x100);
    initial.cache.write_control(0);
    let mut bus = DirectBus::new();
    bus.program(&[0xE101, 0x7101, 0x6213, 0x3120]);
    let mut native = NativeExecutor::default();
    compare_direct(&initial, &bus, 4, &mut native);
    let before = native.block_instructions();
    compare_direct(&initial, &bus, 4, &mut native);
    if cfg!(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"))) {
        assert_eq!(native.block_instructions() - before, 4);
    }
}

#[test]
#[ignore = "extended ARM64 direct/cache fast-fetch acceptance: every legal encoding is an inlined native fast body"]
fn every_legal_opcode_inside_scoped_fast_fetch_blocks() {
    assert!(cfg!(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"))));
    let table = DirectBus::opcode_table();
    let illegal = table.decode(0xFFFF);
    let mut count = 0;
    for cached in [false, true] {
        for opcode in 0..=u16::MAX {
            let reserved = if opcode >> 12 == 8 {
                !matches!((opcode >> 8) & 15, 0 | 1 | 4 | 5 | 8 | 9 | 11 | 13 | 15)
            } else {
                std::ptr::fn_addr_eq(table.decode(opcode), illegal)
            };
            if reserved {
                continue;
            }
            let bank = if cached { 0 } else { 0x20000000 };
            let mut initial = cpu();
            initial.trace_log_enabled = false;
            initial.registers.gpr = std::array::from_fn(|r| bank | 0x800 | r as u32 * 4);
            initial.registers.gbr = bank | 0x800;
            initial.registers.sr =
                crate::registers::StatusRegister::from(u32::from(opcode).rotate_left(7));
            if cached {
                initial.set_pc(0x100);
                initial.cache.write_control(1);
            }
            let mut bus = DirectBus::new();
            bus.program(&[0x0009, opcode]);
            let mut native = NativeExecutor::default();
            let (mut warm, bus) = compare_direct(&initial, &bus, 2, &mut native);
            native.finish_trace();
            // Restore input registers while retaining the actually populated instruction cache.
            warm.registers = initial.registers.clone();
            warm.set_pc(initial.pc());
            compare_direct(&warm, &bus, 2, &mut native);
            require_native(&native, 4);
            assert_eq!(
                native.fast_instructions(),
                2,
                "opcode {opcode:04X} must execute via scoped fast fetch"
            );
            count += 1;
        }
    }
    assert_eq!(count, 107744);
    println!("SH2 full scoped fast-fetch execution proof: {count} cases");
}

#[test]
fn fused_pure_runs_preserve_random_registers_aliases_and_every_exit_boundary() {
    let mut random = 0x51A7E123u32;
    let mut total_fast = 0;
    for cached in [false, true] {
        for sample in 0..64 {
            let mut next = || {
                random ^= random << 13;
                random ^= random >> 17;
                random ^= random << 5;
                random
            };
            let data = std::array::from_fn(|_| next());
            let mut initial = cpu();
            initial.trace_log_enabled = false;
            initial.registers.gpr = data;
            initial.registers.sr.t = sample & 1 != 0;
            if cached {
                initial.set_pc(0x100);
                initial.cache.write_control(1);
            }
            let ops: Vec<_> = (0..16)
                .map(|_| {
                    let value = next();
                    let n = (value as u16 >> 8) & 15;
                    let m = (value as u16 >> 4) & 15;
                    match value % 36 {
                        0 => 0xE000 | (n << 8) | (value as u16 & 255),
                        1 => 0x7000 | (n << 8) | (value as u16 & 255),
                        i @ 22..=27 => [0x3000, 0x3002, 0x3003, 0x3006, 0x3007, 0x2008]
                            [i as usize - 22] | (n << 8) | (m << 4),
                        28 => 0x4010 | (n << 8),
                        29 => 0x4011 | (n << 8),
                        30 => 0x4015 | (n << 8),
                        31 => 0x0029 | (n << 8),
                        32 => 0x8800 | (value as u16 & 255),
                        33 => 0xC800 | (value as u16 & 255),
                        34 => 0x0008,
                        35 => 0x0018,
                        i => {
                            [
                                0x6003, 0x300C, 0x3008, 0x2009, 0x200A, 0x200B, 0x6007, 0x600B,
                                0x600C, 0x600D, 0x600E, 0x600F, 0x6008, 0x6009, 0x200D, 0x6003,
                                0x300C, 0x200A, 0x6008, 0x200D,
                            ][i as usize - 2]
                                | (n << 8)
                                | (m << 4)
                        }
                    }
                })
                .collect();
            let mut bus = DirectBus::new();
            bus.program(&ops);
            let mut native = NativeExecutor::default();
            let (mut warm, bus) = compare_direct(&initial, &bus, 16, &mut native);
            native.finish_trace();
            warm.registers = initial.registers.clone();
            warm.set_pc(initial.pc());
            for budget in 1..=16 {
                compare_direct(&warm, &bus, budget, &mut native);
            }
            for limit in 1..=48 {
                let mut stopped = bus.clone();
                stopped.limit = stopped.cycles + limit;
                compare_direct(&warm, &stopped, 16, &mut native);
            }
            total_fast += native.fast_instructions();
            assert!(sample < 64);
        }
    }
    if cfg!(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"))) {
        assert!(total_fast > 1024);
    }
}

#[test]
fn fast_linked_wait_loops_preserve_every_read_branch_cycle_and_slice_boundary() {
    let mut native = NativeExecutor::default();
    for cached in [false, true] {
        let mut initial = cpu();
        initial.trace_log_enabled = false;
        initial.registers.gpr[0] = if cached { 0x800 } else { 0x20000800 };
        initial.registers.gpr[1] = 0x3400340D;
        if cached {
            initial.set_pc(0x100);
            initial.cache.write_control(1);
        }
        let mut bus = DirectBus::new();
        bus.program(&[0x6202, 0x3120, 0x89FC]);
        let (mut warm, bus) = compare_direct(&initial, &bus, 3, &mut native);
        native.finish_trace();
        warm.set_pc(initial.pc());
        for ticks in 1..=64 {
            compare_direct(&warm, &bus, ticks, &mut native);
        }
        for stop in 1..=96 {
            let mut limited = bus.clone();
            limited.limit = limited.cycles + stop;
            compare_direct(&warm, &limited, 64, &mut native);
        }
    }
    if cfg!(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"))) {
        assert!(native.fast_instructions() > 1024);
    }
}

#[test]
fn legal_guard_mismatch_runs_the_already_fetched_opcode_natively() {
    let mut native = NativeExecutor::default();
    let mut initial = cpu();
    initial.registers.gpr[2] = 0x20000800;
    let mut bus = RecordingBus::new();
    bus.program(0x100, &[0x0009, 0x0009]);
    compare(&initial, &bus, 2, &mut native);
    bus.program(0x102, &[0x6322]);
    bus.put(0x800, &[0x12, 0x34, 0x56, 0x78]);
    compare(&initial, &bus, 2, &mut native);
    require_native(&native, 4);
    if cfg!(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"))) {
        assert_eq!(native.interpreted_instructions(), 0);
        assert_eq!(native.block_instructions(), 1);
        assert_eq!(native.guard_exits(), 1);
    }
}
