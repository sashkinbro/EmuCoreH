use super::*;
use crate::bus::OpSize;
use std::cell::Cell;

thread_local! { static TABLE_ACQUISITIONS: Cell<usize> = const { Cell::new(0) }; }

#[derive(Clone)]
struct Bus {
    opcode: u16,
    cycles: u64,
    events: Vec<(bool, u8, u32, u32, String)>,
}
impl BusInterface for Bus {
    type DebugView<'a> = crate::debug::DummySh2Debugger;
    fn read<const SIZE: u8>(&mut self, address: u32, ctx: AccessContext) -> u32 {
        let value = if matches!(ctx, AccessContext::Fetch) {
            u32::from(self.opcode)
        } else {
            address.wrapping_mul(0x9E3779B9).rotate_left(11) & OpSize::mask::<SIZE>()
        };
        self.events.push((false, SIZE, address, value, ctx.to_string()));
        value
    }
    fn read_cache_line(&mut self, address: u32, ctx: AccessContext) -> [u16; 8] {
        std::array::from_fn(|i| self.read_word(address + 2 * i as u32, ctx))
    }
    fn write<const SIZE: u8>(&mut self, address: u32, value: u32, ctx: AccessContext) {
        self.events.push((true, SIZE, address, value, ctx.to_string()));
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
        false
    }
    fn opcode_table<'a>() -> &'a OpcodeTable<Self> {
        TABLE_ACQUISITIONS.set(TABLE_ACQUISITIONS.get() + 1);
        static TABLE: std::sync::LazyLock<OpcodeTable<Bus>> =
            std::sync::LazyLock::new(OpcodeTable::new);
        &TABLE
    }
}
fn cpu() -> Sh2 {
    let mut cpu = Sh2::new("interpreter-oracle".into());
    cpu.reset_pending = false;
    cpu.trace_log_enabled = false;
    cpu.registers.pc = 0x20000100;
    cpu.registers.next_pc = 0x20000102;
    cpu.registers.sr.interrupt_mask = 15;
    cpu.registers.gpr = std::array::from_fn(|i| 0x20001000 + i as u32 * 32);
    cpu
}
fn bus(opcode: u16) -> Bus {
    Bus { opcode, cycles: 0, events: Vec::new() }
}

#[test]
fn interpreter_slice_acquires_immutable_decode_table_once() {
    TABLE_ACQUISITIONS.set(0);
    let mut cpu = cpu();
    let mut bus = bus(0x0009);
    cpu.execute(8, &mut bus);
    assert_eq!(bus.cycles, 8);
    assert_eq!(bus.events.len(), 8);
    assert_eq!(TABLE_ACQUISITIONS.get(), 1);
}

struct Reference;
impl ExecutionBody<Bus> for Reference {
    fn opcode(&mut self, cpu: &mut Sh2, bus: &mut Bus, opcode: u16) -> bool {
        Bus::opcode_table().decode(opcode)(cpu, opcode, bus);
        true
    }
}

#[test]
fn every_opcode_preserves_full_cpu_state_bus_order_and_cycle_oracle() {
    for opcode in 0..=u16::MAX {
        for flag in [false, true] {
            let mut actual = cpu();
            actual.registers.sr.t = flag;
            let mut expected = actual.clone();
            let mut actual_bus = bus(opcode);
            let mut expected_bus = actual_bus.clone();
            expected.execute_with_body(1, &mut expected_bus, &mut Reference);
            actual.execute(1, &mut actual_bus);
            let config = bincode::config::standard();
            assert_eq!(
                bincode::encode_to_vec(&actual, config).unwrap(),
                bincode::encode_to_vec(&expected, config).unwrap(),
                "opcode={opcode:04X} T={flag}"
            );
            assert_eq!(actual_bus.events, expected_bus.events, "opcode={opcode:04X} T={flag}");
            assert_eq!(actual_bus.cycles, expected_bus.cycles, "opcode={opcode:04X} T={flag}");
        }
    }
}
