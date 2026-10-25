//! Full-state differential oracle. ARM64 runs must prove actual native execution.
use super::NativeExecutor;
use crate::debug::DummyM68000Debugger;
use crate::{BusInterface, M68000};
use std::cell::RefCell;

#[derive(Clone)]
struct RecordingBus {
    memory: Vec<u8>,
    events: RefCell<Vec<String>>,
    reset: bool,
    halt: bool,
    irq: u8,
}
impl RecordingBus {
    fn new() -> Self {
        Self {
            memory: vec![0; 65536],
            events: RefCell::default(),
            reset: false,
            halt: false,
            irq: 0,
        }
    }
    fn event(&self, s: String) {
        self.events.borrow_mut().push(s);
    }
    fn put(&mut self, address: u32, value: u16) {
        let a = address as usize & 65535;
        self.memory[a] = (value >> 8) as u8;
        self.memory[(a + 1) & 65535] = value as u8;
    }
    fn program(&mut self, address: u32, code: &[u16]) {
        for (i, &op) in code.iter().enumerate() {
            self.put(address + 2 * i as u32, op);
        }
    }
}
impl BusInterface for RecordingBus {
    type DebugView<'a> = DummyM68000Debugger;
    fn read_byte(&mut self, a: u32) -> u8 {
        let v = self.memory[a as usize & 65535];
        self.event(format!("rb {a:X} {v:X}"));
        v
    }
    fn read_word(&mut self, a: u32) -> u16 {
        let i = a as usize & 65535;
        let v = u16::from_be_bytes([self.memory[i], self.memory[(i + 1) & 65535]]);
        self.event(format!("rw {a:X} {v:X}"));
        v
    }
    fn write_byte(&mut self, a: u32, v: u8) {
        self.event(format!("wb {a:X} {v:X}"));
        self.memory[a as usize & 65535] = v;
    }
    fn write_word(&mut self, a: u32, v: u16) {
        self.event(format!("ww {a:X} {v:X}"));
        self.put(a, v);
    }
    fn interrupt_level(&self) -> u8 {
        self.event("irq?".into());
        self.irq
    }
    fn acknowledge_interrupt(&mut self, level: u8) {
        self.event(format!("ack {level}"));
    }
    fn halt(&self) -> bool {
        self.event("halt?".into());
        self.halt
    }
    fn reset(&self) -> bool {
        self.event("reset?".into());
        self.reset
    }
}
fn state(cpu: &M68000) -> Vec<u8> {
    bincode::encode_to_vec(cpu, bincode::config::standard()).unwrap()
}
fn setup(code: &[u16], sr: u16) -> (M68000, RecordingBus) {
    let mut bus = RecordingBus::new();
    bus.program(0x100, code);
    bus.program(0, &[0, 0xF000, 0, 0x100]);
    for vector in [3, 4, 8, 10, 11, 24 + 6, 32] {
        bus.program(vector * 4, &[0, 0x100]);
    }
    let mut cpu = M68000::default();
    cpu.set_status_register(sr);
    cpu.set_supervisor_stack_pointer(0xF000);
    cpu.set_pc(0x100, &mut bus);
    bus.events.borrow_mut().clear();
    (cpu, bus)
}
fn step(cpu: &mut M68000, bus: &mut RecordingBus, native: &mut NativeExecutor) -> u32 {
    let mut oracle = cpu.clone();
    let mut expected = bus.clone();
    let cycles = oracle.execute_instruction(&mut expected);
    let actual_cycles = cpu.execute_instruction_with_native(bus, native);
    assert_eq!(actual_cycles, cycles, "cycles");
    assert_eq!(state(cpu), state(&oracle), "full CPU state");
    assert_eq!(bus.memory, expected.memory, "memory");
    assert_eq!(bus.events, expected.events, "ordered bus events");
    bus.events.borrow_mut().clear();
    cycles
}
fn require_native(native: &NativeExecutor, expected: u64) {
    let expected =
        if cfg!(all(target_arch = "aarch64", any(target_os = "android", target_os = "linux"))) {
            expected
        } else {
            0
        };
    assert_eq!(
        native.native_instructions(),
        expected,
        "native execution must not silently fall back"
    );
}
#[test]
fn register_opcodes_all_immediates_registers_and_flags() {
    let mut native = NativeExecutor::default();
    let mut count = 0;
    for r in 0..8 {
        for imm in 0..256 {
            for flags in 0..32 {
                let (mut cpu, mut bus) = setup(&[0x7000 | (r << 9) | imm, 0x4E71], 0xA000 | flags);
                cpu.set_data_registers(std::array::from_fn(|i| 0xDEAD0000 | (i as u32 * 0x123)));
                step(&mut cpu, &mut bus, &mut native);
                count += 1;
            }
        }
    }
    for op in [
        0x4840, 0x4880, 0x48C0, 0x4200, 0x4240, 0x4280, 0x4600, 0x4640, 0x4680, 0x4A00, 0x4A40,
        0x4A80,
    ] {
        for r in 0..8 {
            for value in [
                0, 1, 0x7F, 0x80, 0xFF, 0x100, 0x7FFF, 0x8000, 0xFFFF, 0x80000000, 0xFFFFFFFF,
                0x12345678,
            ] {
                for flags in 0..32 {
                    let (mut cpu, mut bus) = setup(&[op | r, 0x4E71], 0x2000 | flags);
                    let mut data = [0xABCD1234; 8];
                    data[r as usize] = value;
                    cpu.set_data_registers(data);
                    step(&mut cpu, &mut bus, &mut native);
                    count += 1;
                }
            }
        }
    }
    require_native(&native, count);
}
#[test]
fn mixed_fallback_irq_stop_halt_reset_and_prefetched_self_modification() {
    let (mut cpu, mut bus) = setup(&[0x7001, 0x4E71, 0x5280, 0x2080, 0x6000, 0, 0x4E40], 0x2000);
    cpu.set_address_registers([0x200; 7], 0xE000, 0xF000);
    let mut native = NativeExecutor::default();
    step(&mut cpu, &mut bus, &mut native);
    // Next NOP was prefetched. Replacing memory must not change this instruction.
    bus.put(0x102, 0x72FF);
    step(&mut cpu, &mut bus, &mut native);
    assert_eq!(cpu.data_registers()[1], 0);
    step(&mut cpu, &mut bus, &mut native); // ADDQ fallback
    step(&mut cpu, &mut bus, &mut native); // MOVE.L memory fallback
    bus.irq = 6;
    assert_eq!(step(&mut cpu, &mut bus, &mut native), 10);
    assert_eq!(step(&mut cpu, &mut bus, &mut native), 44);
    bus.irq = 0;
    bus.halt = true;
    assert_eq!(step(&mut cpu, &mut bus, &mut native), 1);
    bus.halt = false;
    bus.reset = true;
    assert_eq!(step(&mut cpu, &mut bus, &mut native), 132);
    require_native(&native, 2);
    let (mut stopped, mut sb) = setup(&[0x4E72, 0x2000, 0x7001], 0x2000);
    step(&mut stopped, &mut sb, &mut native);
    assert_eq!(step(&mut stopped, &mut sb, &mut native), 4);
    sb.irq = 6;
    assert_eq!(step(&mut stopped, &mut sb, &mut native), 10);
    assert_eq!(step(&mut stopped, &mut sb, &mut native), 44);
}
#[test]
fn independent_genesis_and_cd_instances_restore_and_disabled_executor() {
    let mut native = NativeExecutor::default();
    for (index, op) in [0x7001, 0x72FF, 0x7400].into_iter().enumerate() {
        let (mut cpu, mut bus) = setup(&[op, 0x4E71, 0x4840], 0x201F);
        step(&mut cpu, &mut bus, &mut native);
        let saved = state(&cpu);
        let (mut restored, _): (M68000, usize) =
            bincode::decode_from_slice(&saved, bincode::config::standard()).unwrap();
        step(&mut restored, &mut bus, &mut native);
        assert_eq!(restored.data_registers()[index], [1, u32::MAX, 0][index]);
    }
    require_native(&native, 6);
    native.disable();
    let before = native.native_instructions();
    let (mut cpu, mut bus) = setup(&[0x70FF], 0x201F);
    step(&mut cpu, &mut bus, &mut native);
    assert_eq!(native.native_instructions(), before);
}

#[test]
fn randomized_register_inputs_and_exception_fallback_boundaries() {
    let mut native = NativeExecutor::default();
    let mut seed = 0xC0FFEE11u32;
    for i in 0..2048 {
        seed ^= seed << 13;
        seed ^= seed >> 17;
        seed ^= seed << 5;
        let opcode = [0x4840, 0x4880, 0x48C0][i % 3] | (seed as u16 & 7);
        let (mut cpu, mut bus) = setup(&[opcode, 0x4E71], 0xA000 | (seed as u16 & 31));
        cpu.set_data_registers(std::array::from_fn(|r| seed.rotate_left(r as u32 * 4)));
        step(&mut cpu, &mut bus, &mut native);
    }
    require_native(&native, 2048);
    for code in [
        vec![0x6000, 0x0002, 0x7001, 0x4E71],
        vec![0x4E40],
        vec![0x7100],
        vec![0xA000],
        vec![0xF000],
    ] {
        let (mut cpu, mut bus) = setup(&code, 0xA01F);
        let before = native.native_instructions();
        step(&mut cpu, &mut bus, &mut native);
        assert_eq!(
            native.native_instructions(),
            before,
            "fallback must not claim native execution"
        );
    }
    // Fault while stacking an illegal instruction freezes the CPU until reset.
    let (mut cpu, mut bus) = setup(&[0x7100], 0x2000);
    cpu.set_supervisor_stack_pointer(0xF001);
    step(&mut cpu, &mut bus, &mut native);
    assert_eq!(step(&mut cpu, &mut bus, &mut native), 1);
    bus.reset = true;
    assert_eq!(step(&mut cpu, &mut bus, &mut native), 132);
}
