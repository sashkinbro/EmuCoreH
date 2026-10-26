use super::*;
use std::cell::Cell;

thread_local! { pub(super) static TABLE_DISPATCHES: Cell<usize> = const { Cell::new(0) }; }

fn fixture() -> Sega32XBus {
    let mut master = [0; 2048];
    let mut slave = [0; 1024];
    for bytes in [&mut master[..], &mut slave[..]] {
        bytes[..4].copy_from_slice(&0x26000000u32.to_be_bytes());
        bytes[4..8].copy_from_slice(&0x26010000u32.to_be_bytes());
    }
    let mut bus = Sega32XBus::new(
        TimingMode::Ntsc,
        None,
        &Sega32XEmulatorConfig::default(),
        BootRoms::new([0; 256], master, slave),
    );
    bus.registers.communication_ports = [0x1234, 0x5678, 0x9abc, 0xdef0, 1, 2, 3, 4];
    // The peer loads GBR=$4020 and writes $55 to communication port0 before
    // looping. A synchronized read must observe that write, not its prior value.
    bus.sdram[..10].copy_from_slice(&[
        0xD103, 0x411E, 0xE055, 0xC100, 0xAFFE, 0x0009, 0x0009, 0x0009, 0x0000, 0x4020,
    ]);
    bus
}

fn original_system_read<const SIZE: u8>(bus: &mut Sh2Bus, address: u32, ctx: AccessContext) -> u32 {
    if !(0x4000..=0x402F).contains(&address) {
        return bus.read_00::<SIZE>(address, ctx);
    }
    // Exact old system-register branch, independent from the extracted helper.
    bus.cycle_counter += if SIZE == OpSize::LONGWORD { 2 } else { 1 };
    bus.maybe_sync_other_cpu(address);
    let which = bus.which;
    sh2_read_register::<SIZE>(address, |address| {
        let bus = bus.s32x_bus();
        bus.registers.sh2_read(address, which, &bus.vdp)
    })
}

fn run<const SIZE: u8>(
    initial: &Sega32XBus,
    address: u32,
    counter: u64,
    limit: u64,
    peer_counter: u64,
    with_peer: bool,
    reference: bool,
) -> (u32, u64, Vec<u8>) {
    let mut source = initial.clone();
    let mut peer = Sh2::new("Slave".into());
    let mut peer_cycles = peer_counter;
    let (value, cycles) = {
        let peer = with_peer.then_some((&mut peer, &mut peer_cycles));
        let mut bus = Sh2Bus::create(&mut source, WhichCpu::Master, counter, limit, peer);
        let ctx = AccessContext::Data { pc: 0x06000100, opcode: 0xC517 };
        let value = if reference {
            original_system_read::<SIZE>(&mut bus, address, ctx)
        } else {
            bus.read::<SIZE>(address, ctx)
        };
        (value, bus.cycle_counter)
    };
    let state =
        bincode::encode_to_vec((&source, &peer, peer_cycles), bincode::config::standard()).unwrap();
    (value, cycles, state)
}

#[test]
fn communication_word_reads_avoid_the_indirect_bus_table() {
    TABLE_DISPATCHES.set(0);
    let (value, cycles, _) = run::<{ OpSize::WORD }>(&fixture(), 0x4020, 0, 0, 0, false, false);
    assert_eq!(value, 0x1234);
    assert_eq!(cycles, 1);
    assert_eq!(TABLE_DISPATCHES.get(), 0);
}

fn compare_sizes<const SIZE: u8>() {
    let initial = fixture();
    for address in 0x401E..=0x4031 {
        for (counter, limit, peer_cycles, peer) in [
            (0, 0, 0, false),
            (100, 100, 0, true),
            (40, 80, 0, true),
            (100, 100, 120, true),
            (100, 20, 0, true),
        ] {
            let expected = run::<SIZE>(&initial, address, counter, limit, peer_cycles, peer, true);
            let actual = run::<SIZE>(&initial, address, counter, limit, peer_cycles, peer, false);
            assert_eq!(
                actual, expected,
                "size={SIZE} address={address:08x} cycles={counter}/{limit} peer={peer_cycles}"
            );
        }
    }
}

#[test]
fn communication_route_preserves_values_peer_cpu_full_bus_and_cycles() {
    compare_sizes::<{ OpSize::BYTE }>();
    compare_sizes::<{ OpSize::WORD }>();
    compare_sizes::<{ OpSize::LONGWORD }>();
    let (value, _, _) = run::<{ OpSize::WORD }>(&fixture(), 0x4020, 100, 100, 0, true, false);
    assert_eq!(value, 0x55, "the peer write must occur before the communication read");
}
