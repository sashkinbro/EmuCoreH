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
    assert_eq!(state(&candidate), state(&interpreted), "full CPU state at {:08X}", cpu.pc());
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
    require_native(&native, 2);
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
    // The first stale guard executes its changed opcode in the interpreter, then
    // evicts that block; restored guest state can subsequently learn fresh host code.
    require_native(&native, 5);
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
    for replacement in [0xE1FF, 0x312E, 0xA001] {
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
        false
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
        let mut totals = (0, 0, 0);
        for _ in 0..5 {
            let mut cpu = cpu();
            cpu.trace_log_enabled = false;
            let mut bus = TimingBus { cycles: 0 };
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
            );
        }
        samples.sort_by(f64::total_cmp);
        println!(
            "SH2 mode={} median_ns/instruction={:.3} samples={samples:?} native={} block={} fallback={}",
            ["interpreter", "native-leaf", "guarded-block"][mode],
            samples[2],
            totals.0,
            totals.1,
            totals.2
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
