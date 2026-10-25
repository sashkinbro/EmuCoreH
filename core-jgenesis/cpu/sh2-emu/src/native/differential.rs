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
