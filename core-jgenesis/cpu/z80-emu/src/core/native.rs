//! Opt-in straight-line ARM64 Z80 translation. Guest state owns no executable pointers.

use super::Z80;
use crate::BusInterface;

/// A window can contain several instructions only when the bus explicitly permits it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WindowResult {
    pub t_cycles: u32,
    /// Native guest opcodes, or one interpreter execution step on fallback.
    /// A fallback step may service an interrupt, RESET or BUSREQ instead of an opcode.
    pub instructions: u32,
    pub native: bool,
}

/// Separate host cache; never serialized or cloned with guest state.
#[derive(Default)]
pub struct NativeExecutor {
    #[cfg(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "linux", target_os = "android")
    ))]
    cache: std::collections::VecDeque<Entry>,
}

#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "linux", target_os = "android")
))]
struct Entry {
    pc: u16,
    bytes: Vec<u8>,
    code: jgenesis_common::native_code::PublishedCode,
}

impl NativeExecutor {
    /// Execute at most `budget` native T-cycles, or one exact interpreter instruction.
    /// The interpreter fallback may exceed the budget (as execute_instruction does).
    /// Returns zero if budget < 4 or a tick() instruction is still in flight.
    /// Callers must advance devices by the result before requesting another window.
    pub fn execute_window<B: BusInterface>(
        &mut self,
        cpu: &mut Z80,
        bus: &mut B,
        budget: u32,
    ) -> WindowResult {
        if budget < 4 || cpu.t_cycles_wait != 0 {
            return WindowResult { t_cycles: 0, instructions: 0, native: false };
        }
        #[cfg(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "linux", target_os = "android")
        ))]
        if !cpu.registers.halted && !cpu.registers.interrupt_delay {
            if let Some(bytes) = bus.native_code_window(cpu.pc(), budget) {
                use crate::traits::InterruptLine;
                let pending_nmi = bus.nmi() == InterruptLine::Low
                    && cpu.registers.last_nmi == InterruptLine::High;
                if !bus.reset()
                    && !bus.busreq()
                    && !pending_nmi
                    && !(cpu.registers.iff1 && bus.int() == InterruptLine::Low)
                {
                    let p = plan(
                        &bytes[..bytes.len().min(usize::from(u16::MAX - cpu.pc()) + 1)],
                        budget,
                    );
                    if p.instructions != 0 {
                        let bytes = &bytes[..p.bytes];
                        let found = self
                            .cache
                            .iter()
                            .position(|entry| entry.pc == cpu.pc() && entry.bytes == bytes);
                        let index = if let Some(index) = found {
                            Some(index)
                        } else {
                            jgenesis_common::native_code::PublishedCode::publish(&emit(bytes)).map(
                                |code| {
                                    // FIFO bounded by entries AND actual runtime page-rounded memory.
                                    // Bytes are compared against the current mapping on every entry,
                                    // covering banks, self-modification, reset and state restoration.
                                    let size = code.mapping_len();
                                    while !self.cache.is_empty()
                                        && (self.cache.len() >= 256
                                            || self
                                                .cache
                                                .iter()
                                                .map(|e| e.code.mapping_len())
                                                .sum::<usize>()
                                                + size
                                                > 4 * 1024 * 1024)
                                    {
                                        self.cache.pop_front();
                                    }
                                    self.cache.push_back(Entry {
                                        pc: cpu.pc(),
                                        bytes: bytes.to_vec(),
                                        code,
                                    });
                                    self.cache.len() - 1
                                },
                            )
                        };
                        if let Some(index) = index {
                            // SAFETY: emit produces a leaf extern-C body using only caller-saved
                            // registers. x0 points at a live Registers; all byte accesses use its
                            // compiler-provided field offsets. The RX mapping remains owned here.
                            unsafe {
                                let body: unsafe extern "C" fn(*mut super::Registers) =
                                    std::mem::transmute(self.cache[index].code.entry_pointer());
                                body(&mut cpu.registers);
                            }
                            cpu.registers.pc = cpu.registers.pc.wrapping_add(p.bytes as u16);
                            cpu.registers.r = (cpu.registers.r.wrapping_add(p.instructions as u8)
                                & 0x7f)
                                | (cpu.registers.r & 0x80);
                            cpu.registers.last_nmi = bus.nmi();
                            cpu.stalled = false;
                            return WindowResult {
                                t_cycles: p.cycles,
                                instructions: p.instructions,
                                native: true,
                            };
                        }
                    }
                }
            }
        }
        WindowResult { t_cycles: cpu.execute_instruction(bus), instructions: 1, native: false }
    }
}

#[cfg(test)]
use compiler::Plan;
#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "linux", target_os = "android")
    )
))]
use compiler::{emit, plan};
#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "linux", target_os = "android")
    )
))]
mod compiler {
    use super::super::{Flags, Registers};
    #[derive(Debug, PartialEq, Eq)]
    pub(super) struct Plan {
        pub(super) bytes: usize,
        pub(super) instructions: u32,
        pub(super) cycles: u32,
    }

    pub(super) fn plan(bytes: &[u8], budget: u32) -> Plan {
        let mut result = Plan { bytes: 0, instructions: 0, cycles: 0 };
        while result.instructions < 32 {
            let Some(&opcode) = bytes.get(result.bytes) else { break };
            let (len, cycles) = if matches!(opcode, 0 | 0x07 | 0x0f | 0x17 | 0x1f)
                || (0x40..=0x7f).contains(&opcode) && opcode & 7 != 6 && (opcode >> 3) & 7 != 6
                || (0x80..=0xbf).contains(&opcode) && opcode & 7 != 6
                || opcode < 0x40 && matches!(opcode & 7, 4 | 5) && (opcode >> 3) & 7 != 6
            {
                (1, 4)
            } else if opcode & 0xc7 == 0x06 && opcode != 0x36 || opcode & 0xc7 == 0xc6 {
                (2, 7)
            } else {
                break;
            };
            if result.cycles + cycles > budget || result.bytes + len > bytes.len() {
                break;
            }
            result.bytes += len;
            result.instructions += 1;
            result.cycles += cycles;
        }
        result
    }

    pub(super) fn emit(bytes: &[u8]) -> Vec<u32> {
        use std::mem::offset_of;
        let offsets = [
            offset_of!(Registers, b),
            offset_of!(Registers, c),
            offset_of!(Registers, d),
            offset_of!(Registers, e),
            offset_of!(Registers, h),
            offset_of!(Registers, l),
            0,
            offset_of!(Registers, a),
        ];
        let mut code = Vec::new();
        // x0 = Registers*, w1..w8 hold Z80 B,C,D,E,H,L,(unused),A for the entire block.
        for r in [0usize, 1, 2, 3, 4, 5, 7] {
            code.push(0x39400000 | (offsets[r] as u32) << 10 | (r as u32 + 1));
        }
        code.push(
            0x39400009 | ((offset_of!(Registers, f) + offset_of!(Flags, carry)) as u32) << 10,
        );
        let mut flags_dirty = false;
        let mut pc = 0;
        while pc < bytes.len() {
            let op = bytes[pc];
            pc += 1;
            if op == 0 {
                continue;
            }
            let dest = u32::from((op >> 3) & 7) + 1;
            if matches!(op, 0x07 | 0x0f | 0x17 | 0x1f) {
                if !flags_dirty {
                    for (bit, offset) in [
                        (7, offset_of!(Flags, sign)),
                        (6, offset_of!(Flags, zero)),
                        (2, offset_of!(Flags, overflow)),
                    ] {
                        code.push(0x3940000e | ((offset_of!(Registers, f) + offset) as u32) << 10);
                        code.push(0x2a000000 | 14 << 16 | bit << 10 | 9 << 5 | 9);
                    }
                }
                extract(&mut code, 13, 9, 0, 0);
                extract(
                    &mut code,
                    12,
                    8,
                    if op & 8 == 0 { 7 } else { 0 },
                    if op & 8 == 0 { 7 } else { 0 },
                );
                let input_carry = if op & 0x10 == 0 { 12 } else { 13 };
                if op & 8 == 0 {
                    reg(&mut code, 0x0b000000, 8, 8, 8);
                    reg(&mut code, 0x2a000000, 8, 8, input_carry);
                    extract(&mut code, 8, 8, 0, 7);
                } else {
                    extract(&mut code, 8, 8, 1, 7);
                    code.push(0x2a000000 | input_carry << 16 | 7 << 10 | 8 << 5 | 8);
                }
                imm(&mut code, 14, 0xc4);
                reg(&mut code, 0x0a000000, 9, 9, 14);
                reg(&mut code, 0x2a000000, 9, 9, 12);
                flags_dirty = true;
            } else if op >= 0x80 || op < 0x40 && matches!(op & 7, 4 | 5) {
                let operand = if op & 0xc7 == 0xc6 {
                    let value = bytes[pc];
                    pc += 1;
                    Some(value)
                } else {
                    None
                };
                emit_alu(&mut code, op, operand);
                flags_dirty = true;
            } else if op & 0xc7 == 0x06 {
                code.push(0x52800000 | u32::from(bytes[pc]) << 5 | dest);
                pc += 1;
            } else {
                let src = u32::from(op & 7) + 1;
                code.push(0x2a0003e0 | src << 16 | dest);
            }
        }
        for r in [0usize, 1, 2, 3, 4, 5, 7] {
            code.push(0x39000000 | (offsets[r] as u32) << 10 | (r as u32 + 1));
        }
        if flags_dirty {
            // X/Y intentionally remain untouched, matching this interpreter's contract.
            for (bit, offset) in [
                (7, offset_of!(Flags, sign)),
                (6, offset_of!(Flags, zero)),
                (4, offset_of!(Flags, half_carry)),
                (2, offset_of!(Flags, overflow)),
                (1, offset_of!(Flags, subtract)),
                (0, offset_of!(Flags, carry)),
            ] {
                extract(&mut code, 10, 9, bit, bit);
                code.push(0x3900000a | ((offset_of!(Registers, f) + offset) as u32) << 10);
            }
        }
        code.push(0xd65f03c0);
        code
    }

    fn mov(code: &mut Vec<u32>, dst: u32, src: u32) {
        code.push(0x2a0003e0 | src << 16 | dst);
    }
    fn imm(code: &mut Vec<u32>, dst: u32, value: u32) {
        code.push(0x52800000 | value << 5 | dst);
    }
    fn reg(code: &mut Vec<u32>, base: u32, dst: u32, a: u32, b: u32) {
        code.push(base | b << 16 | a << 5 | dst);
    }
    fn extract(code: &mut Vec<u32>, dst: u32, src: u32, lo: u32, hi: u32) {
        code.push(0x53000000 | lo << 16 | hi << 10 | src << 5 | dst);
    }
    fn flag(code: &mut Vec<u32>, src: u32, bit: u32, target: u32) {
        extract(code, 14, src, bit, bit);
        code.push(0x2a000000 | 14 << 16 | target << 10 | 9 << 5 | 9);
    }
    fn emit_alu(code: &mut Vec<u32>, opcode: u8, immediate: Option<u8>) {
        let incdec = opcode < 0x40;
        let kind = if incdec { if opcode & 1 == 0 { 0 } else { 2 } } else { (opcode >> 3) & 7 };
        let dest = if incdec { u32::from((opcode >> 3) & 7) + 1 } else { 8 };
        mov(code, 11, dest);
        if incdec {
            imm(code, 12, 1);
        } else if let Some(value) = immediate {
            imm(code, 12, u32::from(value));
        } else {
            mov(code, 12, u32::from(opcode & 7) + 1);
        }
        extract(code, 13, 9, 0, 0); // Incoming carry survives flag reconstruction.
        let logical = (4..=6).contains(&kind);
        if logical {
            reg(
                code,
                match kind {
                    4 => 0x0a000000,
                    5 => 0x4a000000,
                    _ => 0x2a000000,
                },
                17,
                11,
                12,
            );
        } else {
            let sub = kind == 2 || kind == 3 || kind == 7;
            let base = if sub { 0x4b000000 } else { 0x0b000000 };
            reg(code, base, 10, 11, 12);
            if kind == 1 || kind == 3 {
                reg(code, base, 10, 10, 13);
            }
            extract(code, 17, 10, 0, 7);
        }
        imm(code, 9, 0x80);
        reg(code, 0x0a000000, 9, 17, 9); // S
        code.push(0x5ac01000 | 17 << 5 | 14); // CLZ; result is 32 iff zero.
        flag(code, 14, 5, 6);
        if logical {
            code.push(0x4a400000 | 17 << 16 | 4 << 10 | 17 << 5 | 14);
            code.push(0x4a400000 | 14 << 16 | 2 << 10 | 14 << 5 | 14);
            code.push(0x4a400000 | 14 << 16 | 1 << 10 | 14 << 5 | 14);
            code.push(0x2a2003e0 | 14 << 16 | 14); // MVN
            flag(code, 14, 0, 2);
            if kind == 4 {
                imm(code, 14, 0x10);
                reg(code, 0x2a000000, 9, 9, 14);
            }
        } else {
            reg(code, 0x4a000000, 14, 11, 12);
            reg(code, 0x4a000000, 14, 14, 17);
            flag(code, 14, 4, 4);
            reg(code, 0x4a000000, 14, 11, 12);
            reg(code, 0x4a000000, 15, 11, 17);
            let sub = kind == 2 || kind == 3 || kind == 7;
            reg(code, if sub { 0x0a000000 } else { 0x0a200000 }, 14, 15, 14);
            flag(code, 14, 7, 2);
            if incdec {
                reg(code, 0x2a000000, 9, 9, 13);
            } else {
                flag(code, 10, 8, 0);
            }
            if sub {
                imm(code, 14, 2);
                reg(code, 0x2a000000, 9, 9, 14);
            }
        }
        if kind != 7 {
            mov(code, dest, 17);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "linux", target_os = "android")
    ))]
    struct PureBus(crate::traits::InMemoryBus);
    #[cfg(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "linux", target_os = "android")
    ))]
    impl crate::BusInterface for PureBus {
        type DebugView<'a> = crate::debug::DummyZ80Debugger;
        fn read_memory(&mut self, a: u16) -> u8 {
            self.0.read_memory(a)
        }
        fn write_memory(&mut self, a: u16, v: u8) {
            self.0.write_memory(a, v);
        }
        fn read_io(&mut self, a: u16) -> u8 {
            self.0.read_io(a)
        }
        fn write_io(&mut self, a: u16, v: u8) {
            self.0.write_io(a, v);
        }
        fn reset(&self) -> bool {
            self.0.reset()
        }
        fn busreq(&self) -> bool {
            false
        }
        fn nmi(&self) -> crate::traits::InterruptLine {
            self.0.nmi()
        }
        fn int(&self) -> crate::traits::InterruptLine {
            self.0.int()
        }
        fn native_code_window(&self, a: u16, _: u32) -> Option<&[u8]> {
            Some(&self.0.memory[usize::from(a)..])
        }
    }

    struct TestBody {
        #[cfg(not(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "linux", target_os = "android")
        )))]
        words: Vec<u32>,
        #[cfg(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "linux", target_os = "android")
        ))]
        published: jgenesis_common::native_code::PublishedCode,
    }
    impl TestBody {
        fn new(words: Vec<u32>) -> Self {
            #[cfg(all(
                target_arch = "aarch64",
                target_endian = "little",
                any(target_os = "linux", target_os = "android")
            ))]
            {
                Self {
                    published: jgenesis_common::native_code::PublishedCode::publish(&words)
                        .unwrap(),
                }
            }
            #[cfg(not(all(
                target_arch = "aarch64",
                target_endian = "little",
                any(target_os = "linux", target_os = "android")
            )))]
            {
                Self { words }
            }
        }
        fn run(&self, registers: &mut super::super::Registers) {
            #[cfg(all(
                target_arch = "aarch64",
                target_endian = "little",
                any(target_os = "linux", target_os = "android")
            ))]
            unsafe {
                let invoke: unsafe extern "C" fn(*mut super::super::Registers) =
                    std::mem::transmute(self.published.entry_pointer());
                invoke(registers);
            }
            #[cfg(not(all(
                target_arch = "aarch64",
                target_endian = "little",
                any(target_os = "linux", target_os = "android")
            )))]
            emulate(&self.words, registers);
        }
    }

    #[test]
    fn decode_stops_before_memory_prefix_halt_and_cycle_boundary() {
        assert_eq!(
            plan(&[0x3e, 0x42, 0x47, 0x00, 0x76], 100),
            Plan { bytes: 4, instructions: 3, cycles: 15 }
        );
        assert_eq!(plan(&[0x3e, 0x42, 0x47], 10), Plan { bytes: 2, instructions: 1, cycles: 7 });
        for opcode in [0x76, 0x46, 0x70, 0xcb, 0xdd, 0xed, 0xfd, 0xfb, 0xf3, 0xd3] {
            assert_eq!(plan(&[0x00, opcode, 0x00], 100).bytes, 1, "{opcode:02x}");
        }
        assert_eq!(plan(&[0x3e], 100).bytes, 0);
        assert_eq!(plan(&[0x00], 3).bytes, 0);
    }

    // Executes the tiny emitted A64 subset against byte-addressed register storage.
    // This catches emitter register/offset errors on non-ARM hosts; ARM tests below
    // separately execute the real published code.
    #[cfg(not(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "linux", target_os = "android")
    )))]
    fn emulate(code: &[u32], registers: &mut super::super::Registers) {
        let mut w = [0u32; 32];
        for &word in code {
            if word == 0xd65f03c0 {
                break;
            }
            let rd = (word & 31) as usize;
            if word & 0xffc00000 == 0x39400000 {
                let offset = ((word >> 10) & 4095) as usize;
                w[rd] = u32::from(unsafe { *((registers as *const _ as *const u8).add(offset)) });
            } else if word & 0xffc00000 == 0x39000000 {
                let offset = ((word >> 10) & 4095) as usize;
                unsafe {
                    *((registers as *mut _ as *mut u8).add(offset)) = w[rd] as u8;
                }
            } else if word & 0xffe00000 == 0x52800000 {
                w[rd] = (word >> 5) & 65535;
            } else if word & 0xffc00000 == 0x53000000 {
                let lo = (word >> 16) & 31;
                let hi = (word >> 10) & 31;
                assert!(hi >= lo);
                w[rd] = (w[((word >> 5) & 31) as usize] >> lo) & (u32::MAX >> (31 - (hi - lo)));
            } else if word & 0xfffffc00 == 0x5ac01000 {
                w[rd] = w[((word >> 5) & 31) as usize].leading_zeros();
            } else if matches!(
                word & 0xff200000,
                0x0a000000 | 0x0a200000 | 0x2a000000 | 0x2a200000 | 0x4a000000
            ) {
                let a = w[((word >> 5) & 31) as usize];
                let mut b = w[((word >> 16) & 31) as usize];
                let shift = (word >> 10) & 63;
                b = match (word >> 22) & 3 {
                    0 => b << shift,
                    1 => b >> shift,
                    _ => panic!("shift"),
                };
                if word & 0x200000 != 0 {
                    b = !b;
                }
                w[rd] = match word & 0x60000000 {
                    0 => a & b,
                    0x20000000 => a | b,
                    _ => a ^ b,
                };
            } else if matches!(word & 0xff200000, 0x0b000000 | 0x4b000000) {
                let a = w[((word >> 5) & 31) as usize];
                let b = w[((word >> 16) & 31) as usize];
                w[rd] = if word & 0x40000000 == 0 { a.wrapping_add(b) } else { a.wrapping_sub(b) };
            } else {
                panic!("unknown emitted instruction {word:08x}");
            }
            w[31] = 0;
        }
    }

    #[test]
    fn emitted_register_resident_blocks_match_interpreter() {
        use crate::traits::InMemoryBus;
        for seed in 0..256u16 {
            let mut bus = InMemoryBus::new();
            let mut program = Vec::new();
            for r in [0u8, 1, 2, 3, 4, 5, 7] {
                program.extend([0x06 | r << 3, (seed as u8).wrapping_add(r * 17)]);
            }
            for r in [0u8, 1, 2, 3, 4, 5, 7] {
                program.push(0x40 | r << 3 | ((r + 1) % 6));
            }
            program.push(0);
            bus.memory[..program.len()].copy_from_slice(&program);
            let mut expected = crate::Z80::new();
            let mut actual = expected.registers.clone();
            let p = plan(&program, 1000);
            for _ in 0..p.instructions {
                expected.execute_instruction(&mut bus);
            }
            TestBody::new(emit(&program)).run(&mut actual);
            for (a, e) in [
                (actual.a, expected.registers.a),
                (actual.b, expected.registers.b),
                (actual.c, expected.registers.c),
                (actual.d, expected.registers.d),
                (actual.e, expected.registers.e),
                (actual.h, expected.registers.h),
                (actual.l, expected.registers.l),
            ] {
                assert_eq!(a, e, "seed {seed}");
            }
            assert_eq!(actual.f, expected.registers.f);
        }
    }

    #[test]
    fn fallback_preserves_interrupt_prefix_halt_reset_and_tick_continuation() {
        use crate::traits::{InMemoryBus, InterruptLine};
        for program in [
            &[0xfb, 0x00][..],
            &[0xdd, 0xfd, 0x21, 0x12, 0x34],
            &[0x76],
            &[0xed, 0x44],
            &[0xcb, 0x00],
        ] {
            for mode in 0..4 {
                let mut bus = InMemoryBus::new();
                bus.memory[..program.len()].copy_from_slice(program);
                bus.reset = mode == 1;
                bus.nmi = if mode == 2 { InterruptLine::Low } else { InterruptLine::High };
                bus.int = if mode == 3 { InterruptLine::Low } else { InterruptLine::High };
                let mut expected_bus = bus.clone();
                let mut actual = Z80::new();
                actual.registers.iff1 = true;
                let mut expected = actual.clone();
                let mut executor = NativeExecutor::default();
                for _ in 0..3 {
                    let cycles = expected.execute_instruction(&mut expected_bus);
                    let result = executor.execute_window(&mut actual, &mut bus, 100);
                    assert_eq!(result.t_cycles, cycles);
                    assert_eq!(format!("{actual:?}"), format!("{expected:?}"));
                    assert_eq!(bus.memory, expected_bus.memory);
                }
            }
        }
        let mut cpu = Z80::new();
        let mut bus = InMemoryBus::new();
        cpu.tick(&mut bus);
        let before = format!("{cpu:?}");
        assert_eq!(NativeExecutor::default().execute_window(&mut cpu, &mut bus, 100).t_cycles, 0);
        assert_eq!(format!("{cpu:?}"), before);
    }

    #[test]
    fn emitted_alu_flags_match_all_operand_pairs_and_carries() {
        let mut bus = crate::traits::InMemoryBus::new();
        for opcode in [0x80, 0x88, 0x90, 0x98, 0xa0, 0xa8, 0xb0, 0xb8] {
            assert_eq!(plan(&[opcode], 4).instructions, 1, "ALU {opcode:02x}");
            let code = TestBody::new(emit(&[opcode]));
            bus.memory[0] = opcode;
            for a in 0..=255u8 {
                for b in 0..=255u8 {
                    for f in [0u8, 0xff] {
                        let mut expected = Z80::new();
                        expected.registers.a = a;
                        expected.registers.b = b;
                        expected.registers.f = f.into();
                        let mut actual = expected.registers.clone();
                        assert_eq!(expected.execute_instruction(&mut bus), 4);
                        code.run(&mut actual);
                        assert_eq!(
                            (actual.a, actual.f),
                            (expected.registers.a, expected.registers.f),
                            "op={opcode:02x}, a={a}, b={b}, f={f}"
                        );
                    }
                }
            }
        }
    }

    #[test]
    fn emitted_increment_decrement_preserve_carry_and_xy() {
        let mut bus = crate::traits::InMemoryBus::new();
        for r in [0u8, 1, 2, 3, 4, 5, 7] {
            for low in [4, 5] {
                let opcode = r << 3 | low;
                assert_eq!(plan(&[opcode], 4).instructions, 1);
                bus.memory[0] = opcode;
                let code = TestBody::new(emit(&[opcode]));
                for value in 0..=255u8 {
                    for f in [0u8, 0xff] {
                        let mut expected = Z80::new();
                        expected.registers.a = value;
                        expected.registers.b = value;
                        expected.registers.c = value;
                        expected.registers.d = value;
                        expected.registers.e = value;
                        expected.registers.h = value;
                        expected.registers.l = value;
                        expected.registers.f = f.into();
                        let mut actual = expected.registers.clone();
                        expected.execute_instruction(&mut bus);
                        code.run(&mut actual);
                        actual.pc = expected.registers.pc;
                        actual.r = expected.registers.r;
                        assert_eq!(
                            format!("{actual:?}"),
                            format!("{:?}", expected.registers),
                            "op={opcode:02x}, value={value}, f={f}"
                        );
                    }
                }
            }
        }
    }

    #[test]
    fn immediate_alu_and_register_aliases_match_interpreter() {
        let mut bus = crate::traits::InMemoryBus::new();
        for kind in 0..8u8 {
            for src in [0u8, 1, 2, 3, 4, 5, 7, 8] {
                let opcode = if src == 8 { 0xc6 | kind << 3 } else { 0x80 | kind << 3 | src };
                let program = [opcode, 0x81];
                let len = if src == 8 { 2 } else { 1 };
                assert_eq!(plan(&program[..len], 7).instructions, 1);
                let code = TestBody::new(emit(&program[..len]));
                bus.memory[..2].copy_from_slice(&program);
                for value in 0..=255u8 {
                    let mut expected = Z80::new();
                    expected.registers.a = value;
                    expected.registers.b = value.wrapping_mul(17);
                    expected.registers.c = value.wrapping_add(3);
                    expected.registers.d = value.wrapping_add(5);
                    expected.registers.e = value.wrapping_mul(31);
                    expected.registers.h = !value;
                    expected.registers.l = value.rotate_left(1);
                    expected.registers.f = value.into();
                    let mut actual = expected.registers.clone();
                    expected.execute_instruction(&mut bus);
                    code.run(&mut actual);
                    actual.pc = expected.registers.pc;
                    actual.r = expected.registers.r;
                    assert_eq!(
                        format!("{actual:?}"),
                        format!("{:?}", expected.registers),
                        "opcode={opcode:02x}, value={value}"
                    );
                }
            }
        }
    }

    #[test]
    fn accumulator_rotates_preserve_other_flags_for_all_inputs() {
        let mut bus = crate::traits::InMemoryBus::new();
        for opcode in [0x07, 0x0f, 0x17, 0x1f] {
            assert_eq!(plan(&[opcode], 4).instructions, 1);
            bus.memory[0] = opcode;
            let body = TestBody::new(emit(&[opcode]));
            for a in 0..=255u8 {
                for f in 0..=255u8 {
                    let mut expected = Z80::new();
                    expected.registers.a = a;
                    expected.registers.f = f.into();
                    let mut actual = expected.registers.clone();
                    expected.execute_instruction(&mut bus);
                    body.run(&mut actual);
                    assert_eq!(
                        (actual.a, actual.f),
                        (expected.registers.a, expected.registers.f),
                        "op={opcode}, a={a}, f={f}"
                    );
                }
            }
        }
    }

    #[cfg(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "linux", target_os = "android")
    ))]
    #[test]
    fn actual_arm64_published_block_matches_interpreter_and_changed_bank() {
        use crate::traits::InMemoryBus;
        let mut executor = NativeExecutor::default();
        for seed in 0..256u16 {
            let mut bus = PureBus(InMemoryBus::new());
            bus.0.memory[..6].copy_from_slice(&[0x3e, seed as u8, 0x47, 0x4f, 0x00, 0x76]);
            let mut reference = bus.0.clone();
            let mut expected = Z80::new();
            expected.registers.r = seed as u8;
            let mut actual = expected.clone();
            let mut cycles = 0;
            for _ in 0..4 {
                cycles += expected.execute_instruction(&mut reference);
            }
            let result = executor.execute_window(&mut actual, &mut bus, cycles);
            assert!(result.native, "must execute actual ARM code");
            assert_eq!(result.instructions, 4);
            assert_eq!(result.t_cycles, cycles);
            assert_eq!(format!("{actual:?}"), format!("{expected:?}"), "seed {seed}");
        }
        // Mixed flag dependencies, cached reuse, changed immediate bytes, reduced
        // budgets, and the 16-bit fetch wrap all use the full architectural oracle.
        for start in [0u16, 0xfff8, 0xffff] {
            for budget in 4..=64 {
                for seed in [0u8, 0x7f, 0x80, 0xff] {
                    let program = [
                        0x3e, seed, 0x06, !seed, 0x88, 0x04, 0x98, 0xfe, seed, 0xaf, 0x3d, 0x17,
                        0x4f, 0x76,
                    ];
                    let mut bus = PureBus(InMemoryBus::new());
                    for (i, &byte) in program.iter().enumerate() {
                        bus.0.memory[usize::from(start.wrapping_add(i as u16))] = byte;
                    }
                    let mut reference = bus.0.clone();
                    let mut expected = Z80::new();
                    expected.set_pc(start);
                    expected.registers.f = seed.into();
                    expected.registers.r = seed;
                    let mut actual = expected.clone();
                    let result = executor.execute_window(&mut actual, &mut bus, budget);
                    let mut cycles = 0;
                    for _ in 0..result.instructions {
                        cycles += expected.execute_instruction(&mut reference);
                    }
                    assert_eq!(result.t_cycles, cycles);
                    assert_eq!(
                        format!("{actual:?}"),
                        format!("{expected:?}"),
                        "start={start}, budget={budget}, seed={seed}"
                    );
                    assert_eq!(bus.0.memory, reference.memory);
                }
            }
        }
        assert!(executor.cache.len() <= 256);
        assert!(
            executor.cache.iter().map(|entry| entry.code.mapping_len()).sum::<usize>()
                <= 4 * 1024 * 1024
        );
    }
    #[cfg(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "linux", target_os = "android")
    ))]
    #[test]
    #[ignore = "synthetic window throughput only; never a gameplay performance claim"]
    fn benchmark_actual_arm64_register_windows() {
        use std::hint::black_box;
        use std::time::Instant;
        let mut bus = PureBus(crate::traits::InMemoryBus::new());
        bus.0.memory[..32]
            .copy_from_slice(&[0x47, 0x4f, 0x51, 0x5a, 0x63, 0x6c, 0x7d, 0x78].repeat(4));
        for instruction_count in [1u32, 2, 3, 32] {
            let budget = instruction_count * 4;
            let mut reference = bus.0.clone();
            let mut native = Z80::new();
            let mut interpreter = native.clone();
            let mut executor = NativeExecutor::default();
            assert!(executor.execute_window(&mut native, &mut bus, budget).native);
            let iterations = 200_000;
            let start = Instant::now();
            for _ in 0..iterations {
                interpreter.set_pc(0);
                for _ in 0..instruction_count {
                    black_box(interpreter.execute_instruction(black_box(&mut reference)));
                }
            }
            let interpreter_time = start.elapsed();
            native = Z80::new();
            let start = Instant::now();
            for _ in 0..iterations {
                native.set_pc(0);
                let result =
                    executor.execute_window(black_box(&mut native), black_box(&mut bus), budget);
                assert!(result.native);
                assert_eq!(result.instructions, instruction_count);
                black_box(result);
            }
            let native_time = start.elapsed();
            assert_eq!(format!("{native:?}"), format!("{interpreter:?}"));
            println!(
                "SYNTHETIC register-only{instruction_count}-op/{budget}T windows: interpreter={interpreter_time:?}, native={native_time:?}, ratio={:.3}x",
                interpreter_time.as_secs_f64() / native_time.as_secs_f64()
            );
        }
    }
}
