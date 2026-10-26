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
    setup_with_tas(code, sr, true)
}
fn setup_with_tas(code: &[u16], sr: u16, allow_tas: bool) -> (M68000, RecordingBus) {
    let mut bus = RecordingBus::new();
    bus.program(0x100, code);
    bus.program(0, &[0, 0xF000, 0, 0x100]);
    for vector in [3, 4, 8, 10, 11, 24 + 6, 32] {
        bus.program(vector * 4, &[0, 0x100]);
    }
    let mut cpu = M68000::builder().allow_tas_writes(allow_tas).build();
    cpu.set_status_register(sr);
    cpu.set_supervisor_stack_pointer(0xF000);
    cpu.set_pc(0x100, &mut bus);
    bus.events.borrow_mut().clear();
    (cpu, bus)
}
fn step(cpu: &mut M68000, bus: &mut RecordingBus, native: &mut NativeExecutor) -> u32 {
    let diagnostic = format!(
        "opcode={:04X} sr={:04X} data={:?}",
        cpu.next_opcode(),
        cpu.status_register(),
        cpu.data_registers()
    );
    let mut oracle = cpu.clone();
    let mut expected = bus.clone();
    let cycles = oracle.execute_instruction(&mut expected);
    let actual_cycles = cpu.execute_instruction_with_native(bus, native);
    assert_eq!(actual_cycles, cycles, "cycles {diagnostic}");
    assert_eq!(state(cpu), state(&oracle), "full CPU state {diagnostic}");
    assert_eq!(bus.memory, expected.memory, "memory {diagnostic}");
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
    step(&mut cpu, &mut bus, &mut native); // native ADDQ
    step(&mut cpu, &mut bus, &mut native); // native MOVE.L memory
    bus.irq = 6;
    assert_eq!(step(&mut cpu, &mut bus, &mut native), 10);
    assert_eq!(step(&mut cpu, &mut bus, &mut native), 44);
    bus.irq = 0;
    bus.halt = true;
    assert_eq!(step(&mut cpu, &mut bus, &mut native), 1);
    bus.halt = false;
    bus.reset = true;
    assert_eq!(step(&mut cpu, &mut bus, &mut native), 132);
    require_native(&native, 4);
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
    for code in [vec![0x4E40], vec![0x7100], vec![0xA000], vec![0xF000]] {
        let (mut cpu, mut bus) = setup(&code, 0xA01F);
        let before = native.native_instructions();
        step(&mut cpu, &mut bus, &mut native);
        #[cfg(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        ))]
        assert_eq!(
            native.native_instructions(),
            before + u64::from(super::memory::special::base_cycles(code[0]).is_some()),
            "exception instructions have explicit native exception exits; reserved encodings remain fallback"
        );
        #[cfg(not(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        )))]
        assert_eq!(native.native_instructions(), before);
    }
    // Fault while stacking an illegal instruction freezes the CPU until reset.
    let (mut cpu, mut bus) = setup(&[0x7100], 0x2000);
    cpu.set_supervisor_stack_pointer(0xF001);
    step(&mut cpu, &mut bus, &mut native);
    assert_eq!(step(&mut cpu, &mut bus, &mut native), 1);
    bus.reset = true;
    assert_eq!(step(&mut cpu, &mut bus, &mut native), 132);
}

#[test]
fn register_alu_sizes_aliases_edges_and_all_flags() {
    let mut native = NativeExecutor::default();
    let mut count = 0;
    let edges = [
        0, 1, 0x7F, 0x80, 0xFF, 0x7FFF, 0x8000, 0xFFFF, 0x7FFFFFFF, 0x80000000, 0xFFFFFFFF,
        0xA55AA55A,
    ];
    for base in
        [0x1000, 0x2000, 0x3000, 0xD000, 0x9000, 0xB000, 0xC000, 0x8000, 0xB100, 0x5000, 0x5100]
    {
        for size in 0..if base < 0x4000 { 1 } else { 3 } {
            for dst in 0..8 {
                for src in 0..8 {
                    for flags in 0..32 {
                        let op = base | (size << 6) | (dst << 9) | src;
                        let (mut cpu, mut bus) = setup(&[op, 0x4E71], 0x2000 | flags);
                        cpu.set_data_registers(std::array::from_fn(|r| {
                            edges[(r + flags as usize) % edges.len()]
                        }));
                        step(&mut cpu, &mut bus, &mut native);
                        count += 1;
                    }
                }
            }
        }
    }
    require_native(&native, count);
}

fn coverage_inventory() -> (usize, usize) {
    use crate::core::instructions::Instruction;
    let mut counts = std::collections::BTreeMap::<&str, usize>::new();
    let mut native = 0;
    let mut legal = 0;
    let mut csv = String::from("opcode,category,instruction\n");
    for opcode in 0..=u16::MAX {
        let instruction = Instruction::decode_from(opcode);
        let reserved = matches!(instruction, Instruction::Illegal { .. })
            && opcode != 0x4AFC
            && opcode >> 12 != 0xA
            && opcode >> 12 != 0xF;
        let emitted = super::emit(opcode).is_some() || super::memory::emit(opcode).is_some();
        assert!(!(reserved && emitted), "native emitter accepted reserved {opcode:04X}");
        let category = if reserved {
            "reserved"
        } else if emitted {
            native += 1;
            legal += 1;
            "native"
        } else {
            legal += 1;
            if opcode == 0x4AFC || opcode >> 12 == 0xA || opcode >> 12 == 0xF {
                "exception_fallback"
            } else {
                "fallback"
            }
        };
        *counts.entry(category).or_default() += 1;
        csv.push_str(&format!("{opcode:04X},{category},\"{instruction:?}\"\n"));
    }
    assert_eq!(counts.values().sum::<usize>(), 65536);
    println!(
        "M68000 coverage {counts:?}; native={native}/{legal} legal (line A/F exception encodings included)"
    );
    if let Ok(dir) = std::env::var("EMUCORE_JIT_INVENTORY_DIR") {
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(std::path::Path::new(&dir).join("m68000-native-coverage.csv"), csv).unwrap();
    }
    (native, legal)
}
#[test]
fn all_opcode_coverage_inventory() {
    coverage_inventory();
}
#[test]
fn full_native_isa_coverage_acceptance_gate() {
    let (native, legal) = coverage_inventory();
    assert_eq!(native, legal, "full M68000 native ISA coverage is still incomplete");
}
#[test]
fn move_all_ea_modes_aliases_and_faults() {
    use crate::core::instructions::Instruction;
    let mut native = NativeExecutor::default();
    let mut count = 0;
    for opcode in 0..=u16::MAX {
        if !matches!(Instruction::decode_from(opcode), Instruction::Move { .. }) {
            continue;
        }
        let (mut cpu, mut bus) = setup(&[opcode, 0x0010, 0x0020, 0x1234, 0x5678, 0x4E71], 0x201F);
        cpu.set_data_registers([
            0x80000000, 0xFFFFFFFF, 0x7F80FF00, 0x89ABCDEF, 0, 1, 0x12345678, 0xA55A55AA,
        ]);
        cpu.set_address_registers(
            [0x2000, 0x2040, 0x2080, 0x20C0, 0x2100, 0x2140, 0x2180],
            0xE000,
            0xF000,
        );
        bus.program(0x2000, &[0x8001, 0x7FFF, 0xABCD, 0x1234]);
        step(&mut cpu, &mut bus, &mut native);
        count += 1;
    }
    require_native(&native, count);
    // Odd read/write addresses, including source postincrement before a read
    // fault and the special low-word-first MOVE.L predecrement destination.
    for op in [0x3018, 0x301F, 0x30C0, 0x3EC0, 0x2100, 0x2F00, 0x2118, 0x2F1F] {
        let (mut cpu, mut bus) = setup(&[op, 0x4E71], 0x201F);
        cpu.set_address_registers([0x2001; 7], 0xF001, 0xF001);
        step(&mut cpu, &mut bus, &mut native);
    }
}
#[test]
fn binary_alu_all_ea_modes_and_flags() {
    let mut native = NativeExecutor::default();
    let mut count = 0;
    for opcode in 0..=u16::MAX {
        let Some((op, _, _, _)) = super::memory::plan(opcode) else { continue };
        if op == super::memory::Op::Move {
            continue;
        }
        for flags in [0, 4, 16, 31] {
            let (mut cpu, mut bus) =
                setup(&[opcode, 0x0010, 0x0020, 0x1234, 0x5678, 0x4E71], 0x2000 | flags);
            cpu.set_data_registers([
                0x80000000, 0xFFFFFFFF, 0x7F80FF00, 0x89ABCDEF, 0, 1, 0x12345678, 0xA55A55AA,
            ]);
            cpu.set_address_registers(
                [0x2000, 0x2040, 0x2080, 0x20C0, 0x2100, 0x2140, 0x2180],
                0xE000,
                0xF000,
            );
            bus.program(0x2000, &[0x8001, 0x7FFF, 0xABCD, 0x1234]);
            step(&mut cpu, &mut bus, &mut native);
            count += 1;
        }
    }
    require_native(&native, count);
}

#[test]
fn control_stack_all_encodings_and_conditions() {
    let mut native = NativeExecutor::default();
    let mut count = 0;
    for opcode in 0..=u16::MAX {
        if super::memory::control::base_cycles(opcode).is_none() {
            continue;
        }
        for flags in 0..32 {
            let (mut cpu, mut bus) =
                setup(&[opcode, 0x0010, 0x0020, 0x1234, 0x5678, 0x4E71], 0x2000 | flags);
            cpu.set_data_registers([
                0, 1, 0xFFFF, 0xFFFF0000, 0xFFFF0001, 0x7FFF, 0x8000, 0x12345678,
            ]);
            cpu.set_address_registers(
                [0x2000, 0x2040, 0x2080, 0x20C0, 0x2100, 0x2140, 0x2180],
                0xE000,
                0xF000,
            );
            bus.program(0xF000, &[0x201F, 0, 0x0200, 0x1234]);
            step(&mut cpu, &mut bus, &mut native);
            count += 1;
        }
    }
    require_native(&native, count);
}
#[test]
fn shifts_rotates_all_encodings_widths_counts_and_flags() {
    let mut native = NativeExecutor::default();
    let mut count = 0;
    for opcode in 0..=u16::MAX {
        if super::memory::shift::base_cycles(opcode).is_none() {
            continue;
        }
        for amount in [0, 1, 7, 8, 15, 16, 31, 32, 63] {
            for flags in [0, 16, 31] {
                let (mut cpu, mut bus) = setup(&[opcode, 0x0010, 0x0020, 0x4E71], 0x2000 | flags);
                let mut regs =
                    [0x80000001, 0x7FFFFFFF, 0x7F, 0x80, 0x7FFF, 0x8000, 0xFFFF, 0xFFFFFFFF];
                if opcode & 0x20 != 0 && opcode & 0x00C0 != 0x00C0 {
                    regs[((opcode >> 9) & 7) as usize] = amount;
                }
                cpu.set_data_registers(regs);
                cpu.set_address_registers([0x2000; 7], 0xE000, 0xF000);
                bus.program(0x2000, &[0x8001, 0x7FFF]);
                step(&mut cpu, &mut bus, &mut native);
                count += 1;
            }
        }
    }
    require_native(&native, count);
}
#[test]
fn bits_multiply_tas_all_encodings_and_flags() {
    let mut native = NativeExecutor::default();
    let mut count = 0;
    for opcode in 0..=u16::MAX {
        if super::memory::misc::base_cycles(opcode).is_none() {
            continue;
        }
        for flags in [0, 4, 16, 31] {
            let (mut cpu, mut bus) = setup_with_tas(
                &[opcode, 0x0010, 0x0020, 0x1234, 0x5678, 0x4E71],
                0x2000 | flags,
                flags & 16 != 0,
            );
            cpu.set_data_registers([
                0x80000000, 0xFFFFFFFF, 0x7F80FF00, 0x89ABCDEF, 0, 1, 0x12345678, 0xA55A55AA,
            ]);
            cpu.set_address_registers(
                [0x2000, 0x2040, 0x2080, 0x20C0, 0x2100, 0x2140, 0x2180],
                0xE000,
                0xF000,
            );
            bus.program(0x2000, &[0x8001, 0x7FFF, 0xABCD, 0x1234]);
            step(&mut cpu, &mut bus, &mut native);
            count += 1;
        }
    }
    require_native(&native, count);
}
#[test]
fn extended_binary_decimal_all_encodings_and_flags() {
    let mut native = NativeExecutor::default();
    let mut count = 0;
    for opcode in 0..=u16::MAX {
        if super::memory::extended::base_cycles(opcode).is_none() {
            continue;
        }
        for flags in 0..32 {
            let (mut cpu, mut bus) =
                setup(&[opcode, 0x0010, 0x0020, 0x1234, 0x5678, 0x4E71], 0x2000 | flags);
            cpu.set_data_registers([
                0x80000000, 0xFFFFFFFF, 0x7F80FF00, 0x89ABCDEF, 0, 1, 0x12345678, 0xA55A55AA,
            ]);
            cpu.set_address_registers(
                [0x2000, 0x2040, 0x2080, 0x20C0, 0x2100, 0x2140, 0x2180],
                0xE000,
                0xF000,
            );
            bus.program(0x1FFC, &[0x8001, 0x7FFF, 0xABCD, 0x1234]);
            step(&mut cpu, &mut bus, &mut native);
            count += 1;
        }
    }
    for left in 0..=255 {
        for right in [0, 1, 9, 10, 15, 16, 0x99, 0xFF] {
            for flags in [0, 4, 16, 20] {
                for opcode in [0xC101, 0x8101, 0x4800] {
                    let (mut cpu, mut bus) = setup(&[opcode, 0x4E71], 0x2000 | flags);
                    cpu.set_data_registers([left, right, 0, 0, 0, 0, 0, 0]);
                    step(&mut cpu, &mut bus, &mut native);
                    count += 1;
                }
            }
        }
    }
    require_native(&native, count);
}

#[test]
fn multi_register_peripheral_transfers_masks_aliases_faults() {
    let mut native = NativeExecutor::default();
    let mut count = 0;
    for opcode in 0..=u16::MAX {
        if super::memory::transfer::base_cycles(opcode).is_none() {
            continue;
        }
        for mask in [
            0, 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 0x8001,
            0xAAAA, 0xFFFF,
        ] {
            let (mut cpu, mut bus) = setup(&[opcode, mask, 0x0010, 0x0020, 0x4E71], 0x201F);
            cpu.set_data_registers([
                0x80000000, 0xFFFFFFFF, 0x7F80FF00, 0x89ABCDEF, 0, 1, 0x12345678, 0xA55A55AA,
            ]);
            cpu.set_address_registers([0x2000; 7], 0xE000, 0xF000);
            bus.program(0x2000, &[0x8001, 0x7FFF, 0xABCD, 0x1234]);
            step(&mut cpu, &mut bus, &mut native);
            count += 1;
        }
    }
    require_native(&native, count);
}
#[test]
fn status_privileged_and_exception_encodings_both_modes() {
    let mut native = NativeExecutor::default();
    let mut count = 0;
    for opcode in 0..=u16::MAX {
        if super::memory::special::base_cycles(opcode).is_none() {
            continue;
        }
        for sr in [0x001F, 0x201F] {
            let (mut cpu, mut bus) = setup(&[opcode, 0xA71F, 0x0010, 0x0020, 0x4E71], sr);
            cpu.set_data_registers([
                0x80000000, 0xFFFFFFFF, 0x7F80FF00, 0x89ABCDEF, 0, 1, 0x12345678, 0xA55A55AA,
            ]);
            cpu.set_address_registers([0x2000; 7], 0xE000, 0xF000);
            bus.program(0x2000, &[0x8001, 0x7FFF, 0xABCD, 0x1234]);
            bus.program(0xF000, &[0x001F, 0, 0x0200]);
            step(&mut cpu, &mut bus, &mut native);
            count += 1;
        }
    }
    require_native(&native, count);
}

#[test]
fn divide_check_all_ea_zero_overflow_timing_and_flags() {
    let mut native = NativeExecutor::default();
    let mut count = 0;
    for opcode in 0..=u16::MAX {
        if super::memory::divide::base_cycles(opcode).is_none() {
            continue;
        }
        let (mut cpu, mut bus) = setup(&[opcode, 0x0010, 0x0020, 0x4E71], 0x201F);
        cpu.set_data_registers([
            0x80000000, 0xFFFFFFFF, 0x7F80FF00, 0x89ABCDEF, 0, 1, 0x12345678, 0xA55A55AA,
        ]);
        cpu.set_address_registers([0x2000; 7], 0xE000, 0xF000);
        bus.program(0x2000, &[0x8001, 0x7FFF]);
        step(&mut cpu, &mut bus, &mut native);
        count += 1;
    }
    for dividend in [0, 1, 0xFFFF, 0x10000, 0x7FFFFFFF, 0x80000000, 0xFFFFFFFF] {
        for divisor in [0, 1, 2, 0x7FFF, 0x8000, 0xFFFF] {
            for flags in 0..32 {
                for opcode in [0x80C1, 0x81C1, 0x4181] {
                    let (mut cpu, mut bus) = setup(&[opcode, 0x4E71], 0x2000 | flags);
                    cpu.set_data_registers([dividend, divisor, 0, 0, 0, 0, 0, 0]);
                    step(&mut cpu, &mut bus, &mut native);
                    count += 1;
                }
            }
        }
    }
    require_native(&native, count);
}
#[test]
fn every_legal_opcode_executes_actual_native_body() {
    use crate::core::instructions::Instruction;
    let mut native = NativeExecutor::default();
    let mut count = 0;
    for opcode in 0..=u16::MAX {
        if matches!(Instruction::decode_from(opcode), Instruction::Illegal { .. })
            && opcode != 0x4AFC
            && opcode >> 12 != 0xA
            && opcode >> 12 != 0xF
        {
            continue;
        }
        let (mut cpu, mut bus) = setup(&[opcode, 0x0001, 0x0020, 0x1234, 0x5678, 0x4E71], 0x201F);
        cpu.set_data_registers([
            0x80000000, 0xFFFFFFFF, 0x7F80FF00, 0x89ABCDEF, 0, 1, 0x12345678, 0xA55A55AA,
        ]);
        cpu.set_address_registers([0x2000; 7], 0xE000, 0xF000);
        bus.program(0x2000, &[0x8001, 0x7FFF]);
        bus.program(0xF000, &[0x201F, 0, 0x0200]);
        step(&mut cpu, &mut bus, &mut native);
        count += 1;
    }
    require_native(&native, count);
}
#[cfg(feature="jit-runtime")]
#[test]
fn native_blocks_inline_bodies_and_stop_at_device_boundaries() {
    let code=[0x7001,0x72FF,0xD001,0x5240,0x4840,0x4880,0x48C0,0x4202,0x4681,0x4A80,0x2080,0x3010,0xD090,0xC101,0xE348,0x4E71];
    let(mut initial,mut initial_bus)=setup(&code,0x201F);initial.set_address_registers([0x2000;7],0xE000,0xF000);initial_bus.program(0x2000,&[0x8001,0x7FFF]);
    let mut native=NativeExecutor::default();let(mut train,mut train_bus)=(initial.clone(),initial_bus.clone());
    for _ in 0..16{step(&mut train,&mut train_bus,&mut native);}
    for stop in [1,4,16] {
        let(mut cpu,mut bus)=(initial.clone(),initial_bus.clone());let(mut oracle,mut expected)=(initial.clone(),initial_bus.clone());let mut completed=0;
        let steps=cpu.execute_block_with_native(&mut bus,&mut native,|_,_|{},|cpu,bus,_,cycles|{
            let expected_cycles=oracle.execute_instruction(&mut expected);assert_eq!(cycles,expected_cycles);assert_eq!(state(cpu),state(&oracle));assert_eq!(bus.memory,expected.memory);assert_eq!(*bus.events.borrow(),*expected.events.borrow());bus.events.borrow_mut().clear();expected.events.borrow_mut().clear();completed+=1;completed<stop
        });
        #[cfg(all(target_arch="aarch64",target_endian="little",any(target_os="android",target_os="linux")))]
        assert_eq!(steps,stop);
        #[cfg(not(all(target_arch="aarch64",target_endian="little",any(target_os="android",target_os="linux"))))]
        assert_eq!(steps,0);
    }
    // IRQs and self-modified *future* prefetch words force a block boundary
    // without re-fetching or omitting an architectural device tick.
    for irq in [true,false] {
        let(mut cpu,mut bus)=(initial.clone(),initial_bus.clone());let(mut oracle,mut expected)=(initial.clone(),initial_bus.clone());let mut completed=0;
        let steps=cpu.execute_block_with_native(&mut bus,&mut native,|_,_|{},|cpu,bus,_,cycles|{
            assert_eq!(cycles,oracle.execute_instruction(&mut expected));assert_eq!(state(cpu),state(&oracle));assert_eq!(*bus.events.borrow(),*expected.events.borrow());bus.events.borrow_mut().clear();expected.events.borrow_mut().clear();completed+=1;
            if completed==if irq{2}else{1} {if irq{bus.irq=6;expected.irq=6;}else{bus.put(0x104,0x4E71);expected.put(0x104,0x4E71);}}
            true
        });
        #[cfg(all(target_arch="aarch64",target_endian="little",any(target_os="android",target_os="linux")))]
        assert_eq!(steps,if irq{3}else{2});
        #[cfg(not(all(target_arch="aarch64",target_endian="little",any(target_os="android",target_os="linux"))))]
        assert_eq!(steps,0);
    }
}
