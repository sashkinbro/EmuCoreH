//! Differential tester and host benchmark for the SH-2 block engine on real 32X ROMs.
//!
//! Modes:
//! * `interp` / `predecode` / `jit`: run the ROM with that backend, print speed and the hash of the
//!   per-frame full emulator state hashes (equal hashes across modes = bit-exact runs);
//! * `verify`: block engine in lockstep with the interpreter (every slice replayed and compared,
//!   see `sh2_emu::set_jit_verify`); panics on the first divergence;
//! * `compare`: two emulators, interpreter and block engine, compared frame by frame by full
//!   serialized state.
//!
//! ROMs and BIOS files are the owner's private files and are only read from the given paths.

use genesis_config::{GenesisButton, GenesisEmulatorConfig, GenesisInputs};
use genesis_core::GenesisEmulator;
use genesis_core::api::GenesisHardware;
use jgenesis_common::frontend::{
    AudioOutput, Color, ConstantInputPoller, EmulatorTrait, FrameSize, MappableInputs,
    RenderFrameOptions, Renderer, SaveWriter, TickEffect,
};
use jgenesis_common::input::Player;
use std::hash::Hasher;
use std::path::PathBuf;
use std::time::Instant;

struct NullRenderer;

impl Renderer for NullRenderer {
    type Err = String;

    fn render_frame(
        &mut self,
        _: &[Color],
        _: FrameSize,
        _: f64,
        _: RenderFrameOptions,
    ) -> Result<(), Self::Err> {
        Ok(())
    }
}

struct NullAudio;

impl AudioOutput for NullAudio {
    type Err = String;

    fn push_sample(&mut self, _: f64, _: f64) -> Result<(), Self::Err> {
        Ok(())
    }
}

struct NullSave;

impl SaveWriter for NullSave {
    type Err = String;

    fn load_bytes(&mut self, _: &str) -> Result<Vec<u8>, Self::Err> {
        Err(String::new())
    }

    fn persist_bytes(&mut self, _: &str, _: &[u8]) -> Result<(), Self::Err> {
        Ok(())
    }

    fn load_serialized<D: bincode::Decode<()>>(&mut self, _: &str) -> Result<D, Self::Err> {
        Err(String::new())
    }

    fn persist_serialized<E: bincode::Encode>(&mut self, _: &str, _: E) -> Result<(), Self::Err> {
        Ok(())
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Mode {
    Interp,
    Predecode,
    Jit,
    Verify,
    Compare,
}

struct Args {
    rom: PathBuf,
    bios: PathBuf,
    frames: u64,
    mode: Mode,
    input: Vec<(u64, u64, u32)>,
    deep: u64,
    hash_every: u64,
}

const DEFAULT_INPUT: &str = "200-205:8,400-405:8,600-605:8,800-805:16384,900-905:8,1000-1500:32,1100-1110:16384,1300-1310:32768,1500-1800:160";

fn parse_input(script: &str) -> Vec<(u64, u64, u32)> {
    script
        .split(',')
        .filter(|s| !s.is_empty())
        .map(|item| {
            let (range, mask) = item.split_once(':').expect("input item is from-to:mask");
            let (from, to) = range.split_once('-').expect("input range is from-to");
            (from.parse().unwrap(), to.parse().unwrap(), mask.parse().unwrap())
        })
        .collect()
}

fn parse_args() -> Args {
    let default_bios = if std::path::Path::new("D:/Sega/bios DreamCust").exists() {
        PathBuf::from("D:/Sega/bios DreamCust")
    } else {
        PathBuf::from("E:/Sega/bios DreamCust")
    };
    let mut args = Args {
        rom: PathBuf::new(),
        bios: default_bios,
        frames: 600,
        mode: Mode::Verify,
        input: parse_input(DEFAULT_INPUT),
        deep: 64,
        hash_every: 1,
    };
    let mut it = std::env::args().skip(1);
    while let Some(arg) = it.next() {
        let mut value = || it.next().unwrap_or_else(|| panic!("{arg} needs a value"));
        match arg.as_str() {
            "--rom" => args.rom = value().into(),
            "--bios" => args.bios = value().into(),
            "--frames" => args.frames = value().parse().unwrap(),
            "--input" => args.input = parse_input(&value()),
            "--deep" => args.deep = value().parse().unwrap(),
            "--hash-every" => args.hash_every = value().parse().unwrap(),
            "--native" => sh2_emu::set_jit_native(value() != "0"),
            "--mode" => {
                args.mode = match value().as_str() {
                    "interp" => Mode::Interp,
                    "predecode" => Mode::Predecode,
                    "jit" => Mode::Jit,
                    "verify" => Mode::Verify,
                    "compare" => Mode::Compare,
                    other => panic!("unknown mode {other}"),
                }
            }
            other => panic!("unknown argument {other}"),
        }
    }
    assert!(!args.rom.as_os_str().is_empty(), "--rom is required");
    args
}

fn inputs_for(script: &[(u64, u64, u32)], frame: u64) -> GenesisInputs {
    let mut mask = 0;
    for &(from, to, m) in script {
        if (from..=to).contains(&frame) {
            mask |= m;
        }
    }
    let mut inputs = GenesisInputs::default();
    for (bit, button) in [
        (8, GenesisButton::Start),
        (16, GenesisButton::Up),
        (32, GenesisButton::Right),
        (64, GenesisButton::Down),
        (128, GenesisButton::Left),
        (16384, GenesisButton::B),
        (8192, GenesisButton::C),
        (32768, GenesisButton::A),
        (1, GenesisButton::Mode),
    ] {
        if mask & bit != 0 {
            inputs.set_field(button, Player::One, true);
        }
    }
    inputs
}

fn create(args: &Args) -> GenesisEmulator {
    let rom = std::fs::read(&args.rom).expect("read ROM");
    let boot_roms = s32x_core::BootRoms::load(&args.bios).expect("load 32X BIOS");
    GenesisEmulator::create_with_32x_boot_roms(
        GenesisHardware::Sega32X,
        Some(rom),
        None,
        None,
        GenesisEmulatorConfig::default(),
        &mut NullSave,
        Some(boot_roms),
    )
    .expect("create emulator")
}

fn select(mode: Mode) {
    sh2_emu::set_predecode_enabled(mode == Mode::Predecode);
    sh2_emu::set_jit_enabled(matches!(mode, Mode::Jit | Mode::Verify));
    #[cfg(feature = "verify")]
    sh2_emu::set_jit_verify(mode == Mode::Verify);
    #[cfg(not(feature = "verify"))]
    assert!(mode != Mode::Verify, "verify mode needs the `verify` feature");
}

fn run_frame(emulator: &mut GenesisEmulator, inputs: &GenesisInputs) {
    loop {
        let effect = emulator
            .tick(&mut NullRenderer, &mut NullAudio, &mut ConstantInputPoller(inputs), &mut NullSave)
            .expect("tick");
        if effect == TickEffect::FrameRendered {
            return;
        }
    }
}

fn state_hash(emulator: &GenesisEmulator) -> u64 {
    let bytes = bincode::encode_to_vec(emulator, bincode::config::standard()).expect("encode");
    let mut hasher = std::hash::DefaultHasher::new();
    hasher.write(&bytes);
    hasher.finish()
}

fn main() {
    let args = parse_args();
    #[cfg(feature = "verify")]
    sh2_emu::set_jit_verify_deep_every(args.deep);
    let name = args.rom.file_name().unwrap().to_string_lossy().into_owned();

    if args.mode == Mode::Compare {
        let mut reference = create(&args);
        let mut subject = create(&args);
        for frame in 0..args.frames {
            let inputs = inputs_for(&args.input, frame);
            select(Mode::Interp);
            run_frame(&mut reference, &inputs);
            select(Mode::Jit);
            run_frame(&mut subject, &inputs);
            let (a, b) = (state_hash(&reference), state_hash(&subject));
            assert_eq!(a, b, "{name}: full state differs after frame {frame}");
        }
        println!("{name}: compare OK, {} frames bit-identical", args.frames);
        return;
    }

    select(args.mode);
    sh2_emu::reset_jit_stats();
    let mut emulator = create(&args);
    let mut chain = std::hash::DefaultHasher::new();
    let mut emulation = std::time::Duration::ZERO;
    for frame in 0..args.frames {
        let inputs = inputs_for(&args.input, frame);
        let start = Instant::now();
        run_frame(&mut emulator, &inputs);
        emulation += start.elapsed();
        if args.hash_every != 0 && (frame + 1) % args.hash_every == 0 {
            chain.write_u64(state_hash(&emulator));
        }
    }
    let final_hash = state_hash(&emulator);
    let stats = sh2_emu::jit_stats();
    println!(
        "{name}: mode {:?} frames {} emu {:.3}s ({:.3} ms/frame) final {final_hash:016x} chain {:016x}",
        args.mode,
        args.frames,
        emulation.as_secs_f64(),
        emulation.as_secs_f64() * 1000.0 / args.frames as f64,
        chain.finish()
    );
    if matches!(args.mode, Mode::Jit | Mode::Verify) {
        println!("  {stats:?}");
    }
}
