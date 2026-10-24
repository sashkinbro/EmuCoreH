use super::*;
use std::sync::atomic::{AtomicUsize, Ordering};

static VIDEO: AtomicUsize = AtomicUsize::new(0);
static AUDIO: AtomicUsize = AtomicUsize::new(0);
static COLOR: AtomicUsize = AtomicUsize::new(0);
static SIGNAL: AtomicUsize = AtomicUsize::new(0);
static TEST_DIRECTORY: OnceLock<CString> = OnceLock::new();
static DISK: OnceLock<retro_disk_control_callback> = OnceLock::new();
static DISK_EXT: OnceLock<retro_disk_control_ext_callback> = OnceLock::new();
static SUPPORT_DISK_EXT: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

unsafe extern "C" fn test_environment(cmd: u32, data: *mut c_void) -> bool {
    if cmd == RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION
        && SUPPORT_DISK_EXT.load(Ordering::Relaxed)
    {
        unsafe {
            *data.cast::<u32>() = 1;
        }
        return true;
    }
    if cmd == RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE {
        let _ = DISK_EXT.set(unsafe { *data.cast::<retro_disk_control_ext_callback>() });
        return true;
    }
    if matches!(
        cmd,
        RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY
            | RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY
            | RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY
    ) {
        unsafe {
            *data.cast::<*const c_char>() = TEST_DIRECTORY.get().unwrap().as_ptr();
        }
        return true;
    }
    if cmd == RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE {
        let _ = DISK.set(unsafe { *data.cast::<retro_disk_control_callback>() });
    }
    matches!(
        cmd,
        RETRO_ENVIRONMENT_SET_PIXEL_FORMAT
            | RETRO_ENVIRONMENT_SET_VARIABLES
            | RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE
    )
}

unsafe extern "C" fn video(data: *const c_void, width: u32, height: u32, pitch: usize) {
    assert!(!data.is_null());
    assert_eq!(pitch, width as usize * 4);
    assert!(width >= 160 && height >= 144);
    VIDEO.fetch_add(1, Ordering::Relaxed);
    let pixels =
        unsafe { std::slice::from_raw_parts(data.cast::<u32>(), width as usize * height as usize) };
    COLOR.fetch_add(pixels.iter().filter(|pixel| **pixel != 0).count(), Ordering::Relaxed);
}

unsafe extern "C" fn audio(data: *const i16, frames: usize) -> usize {
    AUDIO.fetch_add(frames, Ordering::Relaxed);
    let samples = unsafe { std::slice::from_raw_parts(data, frames * 2) };
    SIGNAL.fetch_add(samples.iter().filter(|sample| **sample != 0).count(), Ordering::Relaxed);
    frames
}

fn install_32x_test_bios(directory: &Path) -> bool {
    const NAMES: [&str; 3] = ["32X_G_BIOS.bin", "32X_M_BIOS.bin", "32X_S_BIOS.bin"];
    let candidates = [
        std::env::var_os("JGENESIS_TEST_32X_BIOS_DIR").map(PathBuf::from),
        Some(Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../build/qa-32x-bios")),
    ];
    let Some(source) = candidates
        .into_iter()
        .flatten()
        .find(|source| NAMES.iter().all(|name| source.join(name).is_file()))
    else {
        return false;
    };
    NAMES.iter().all(|name| std::fs::copy(source.join(name), directory.join(name)).is_ok())
}

fn genesis_rom() -> Vec<u8> {
    let mut rom = vec![0; 32768];
    rom[..4].copy_from_slice(&0x00fffff0u32.to_be_bytes());
    rom[4..8].copy_from_slice(&0x00000200u32.to_be_bytes());
    rom[0x100..0x110].copy_from_slice(b"SEGA MEGA DRIVE ");
    rom[0x1f0] = b'U';
    // Set CRAM color zero red, enable display and a PSG tone, then STOP.
    let mut program = Vec::new();
    for (value, address) in [(0xc0000000u32, 0x00c00004u32)] {
        program.extend_from_slice(&[0x23, 0xfc]);
        program.extend_from_slice(&value.to_be_bytes());
        program.extend_from_slice(&address.to_be_bytes());
    }
    for (value, address) in [(0x000eu16, 0x00c00000u32), (0x8700, 0x00c00004), (0x8144, 0x00c00004)]
    {
        program.extend_from_slice(&[0x33, 0xfc]);
        program.extend_from_slice(&value.to_be_bytes());
        program.extend_from_slice(&address.to_be_bytes());
    }
    for value in [0x80u16, 0x10, 0x90] {
        program.extend_from_slice(&[0x13, 0xfc]);
        program.extend_from_slice(&value.to_be_bytes());
        program.extend_from_slice(&0x00c00011u32.to_be_bytes());
    }
    program.extend_from_slice(&[0x4e, 0x72, 0x27, 0x00, 0x60, 0xfa]);
    rom[0x200..0x200 + program.len()].copy_from_slice(&program);
    rom
}

fn smsgg_rom() -> Vec<u8> {
    let mut rom = vec![0; 32768];
    let mut program = vec![0xf3, 0x31, 0xf0, 0xdf];
    // Enable TMS-compatible display with a green backdrop, and a PSG tone.
    for (port, value) in [
        (0xbf, 0x00),
        (0xbf, 0x80),
        (0xbf, 0x40),
        (0xbf, 0x81),
        (0xbf, 0x02),
        (0xbf, 0x87),
        (0x06, 0x3f),
        (0x7f, 0x80),
        (0x7f, 0x10),
        (0x7f, 0x90),
    ] {
        program.extend_from_slice(&[0x3e, value, 0xd3, port]);
    }
    program.extend_from_slice(&[0x18, 0xfe]);
    rom[..program.len()].copy_from_slice(&program);
    rom
}

fn exercise_gg_sms_palette_pause_and_state_identity() {
    // Authored program, not retail data. Its CRC selects the cartridge pin-42 record.
    let mut rom = vec![0; 262144];
    let mut program = vec![0xf3, 0x31, 0xf0, 0xdf];
    for (port, value) in [
        (0xbf, 4),
        (0xbf, 0x80),
        (0xbf, 0x40),
        (0xbf, 0x81),
        (0xbf, 0),
        (0xbf, 0x87),
        (0xbf, 0),
        (0xbf, 0xc0),
        (0xbe, 3),
        (0x7f, 0x80),
        (0x7f, 0x10),
        (0x7f, 0x90),
    ] {
        program.extend_from_slice(&[0x3e, value, 0xd3, port]);
    }
    program.extend_from_slice(&[0x18, 0xfe]);
    rom[..program.len()].copy_from_slice(&program);
    // Pause/NMI changes the SMS backdrop from red to green using one-byte CRAM writes.
    rom[0x66..0x74].copy_from_slice(&[
        0x3e, 0, 0xd3, 0xbf, 0x3e, 0xc0, 0xd3, 0xbf, 0x3e, 0x0c, 0xd3, 0xbe, 0xed, 0x45,
    ]);
    rom[0x7ff0..0x7ff8].copy_from_slice(b"TMR SEGA");
    rom[0x7fff] = 0x60;
    rom[262140..].copy_from_slice(&[0xe2, 0x0b, 0xd5, 0xdc]);
    assert_eq!(crc::Crc::<u32>::new(&crc::CRC_32_ISO_HDLC).checksum(&rom), 0x59840fd6);
    let directory = std::env::temp_dir().join(format!("jgenesis-gg-sms-{}", std::process::id()));
    std::fs::create_dir_all(&directory).unwrap();
    let path = directory.join("authored.gg");
    std::fs::write(&path, &rom).unwrap();
    let mut core = load_core(&path).unwrap();
    assert_eq!(core.console, Console::GameGear);
    let Emulator::SmsGg(emulator) = &core.emulator else { panic!("8-bit core") };
    assert_eq!(emulator.hardware(), SmsGgHardware::MasterSystem);
    assert_eq!((core.av.geometry.base_width, core.av.geometry.base_height), (256, 192));
    for _ in 0..3 {
        core.run_frame(Inputs::SmsGg(SmsGgInputs::default())).unwrap();
    }
    assert_eq!((core.frame.width, core.frame.height), (256, 192));
    assert!(core.frame.pixels.iter().all(|pixel| *pixel == 0xffff0000));
    assert!(core.audio.samples.iter().any(|sample| *sample != 0));
    let mut inputs = SmsGgInputs::default();
    inputs.set_field(SmsGgButton::Pause, Player::One, true);
    for _ in 0..3 {
        core.run_frame(Inputs::SmsGg(inputs)).unwrap();
    }
    assert!(core.frame.pixels.iter().all(|pixel| *pixel == 0xff00ff00));
    core.ensure_state_cache().unwrap();
    let state = core.state_cache.clone();
    assert_eq!(state[8], Console::GameGear as u8);
    core.load_state(&state).unwrap();
    core.run_frame(Inputs::SmsGg(SmsGgInputs::default())).unwrap();
    assert!(core.frame.pixels.iter().all(|pixel| *pixel == 0xff00ff00));
    // The exposed GG crop option must never crop a cartridge that physically uses SMS mode.
    for full_resolution in [false, true] {
        let mut config = options::smsgg(Console::GameGear);
        config.gg_use_sms_resolution = full_resolution;
        let Emulator::SmsGg(emulator) = &mut core.emulator else { unreachable!() };
        emulator.reload_config(&config);
        core.run_frame(Inputs::SmsGg(SmsGgInputs::default())).unwrap();
        assert_eq!((core.frame.width, core.frame.height), (256, 192));
    }
    // Unknown checksums keep native GG mode; legacy states from the broken mode
    // cannot silently put a corrected cartridge back into native GG video hardware.
    rom[262143] ^= 1;
    std::fs::write(&path, &rom).unwrap();
    let mut native_gg = load_core(&path).unwrap();
    let Emulator::SmsGg(emulator) = &native_gg.emulator else { unreachable!() };
    assert_eq!(emulator.hardware(), SmsGgHardware::GameGear);
    assert_eq!((native_gg.av.geometry.base_width, native_gg.av.geometry.base_height), (160, 144));
    native_gg.ensure_state_cache().unwrap();
    assert!(core.load_state(&native_gg.state_cache).is_err());
    core.run_frame(Inputs::SmsGg(SmsGgInputs::default())).unwrap();
    assert!(core.frame.pixels.iter().all(|pixel| *pixel == 0xff00ff00));
    drop(native_gg);
    drop(core);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn libretro_boot_video_audio_states_and_input() {
    // Upstream debug builds copy large 32X state values on the stack.
    std::thread::Builder::new()
        .stack_size(16 * 1024 * 1024)
        .spawn(exercise_libretro)
        .unwrap()
        .join()
        .unwrap();
}

fn exercise_libretro() {
    let directory = std::env::temp_dir().join(format!("jgenesis-smoke-{}", std::process::id()));
    std::fs::create_dir_all(&directory).unwrap();
    let _ = TEST_DIRECTORY.set(CString::new(directory.to_str().unwrap()).unwrap());
    retro_set_environment(Some(test_environment));
    retro_set_video_refresh(Some(video));
    retro_set_audio_sample_batch(Some(audio));
    retro_init();
    let missing_bios_rom = directory.join("missing-bios.32x");
    std::fs::write(&missing_bios_rom, genesis_rom()).unwrap();
    let missing_path = CString::new(missing_bios_rom.to_str().unwrap()).unwrap();
    let missing_game = retro_game_info {
        path: missing_path.as_ptr(),
        data: std::ptr::null(),
        size: 0,
        meta: std::ptr::null(),
    };
    assert!(!retro_load_game(&missing_game), "32X must require user-supplied BIOS");
    assert!(
        unsafe { CStr::from_ptr(jgenesis_boot_error()) }
            .to_bytes()
            .starts_with(b"SEGA_32X_BIOS_REQUIRED:")
    );
    let has_32x_bios = install_32x_test_bios(&directory);
    exercise_gg_sms_palette_pause_and_state_identity();
    let mut info: retro_system_info = unsafe { std::mem::zeroed() };
    retro_get_system_info(&mut info);
    assert_eq!(unsafe { CStr::from_ptr(info.library_version) }.to_bytes(), b"0.14.1");
    let mut previous_state = None;
    for (extension, bytes) in [
        ("md", genesis_rom()),
        ("32x", genesis_rom()),
        ("sms", smsgg_rom()),
        ("gg", smsgg_rom()),
        ("sg", smsgg_rom()),
    ] {
        let path = directory.join(format!("smoke.{extension}"));
        std::fs::write(&path, bytes).unwrap();
        if extension == "32x" && !has_32x_bios {
            let c_path = CString::new(path.to_str().unwrap()).unwrap();
            let game = retro_game_info {
                path: c_path.as_ptr(),
                data: std::ptr::null(),
                size: 0,
                meta: std::ptr::null(),
            };
            assert!(!retro_load_game(&game), "32X without firmware must not load");
            assert!(
                unsafe { CStr::from_ptr(jgenesis_boot_error()) }
                    .to_bytes()
                    .starts_with(b"SEGA_32X_BIOS_REQUIRED:")
            );
            continue;
        }
        let c_path = CString::new(path.to_str().unwrap()).unwrap();
        let game = retro_game_info {
            path: c_path.as_ptr(),
            data: std::ptr::null(),
            size: 0,
            meta: std::ptr::null(),
        };
        assert!(retro_load_game(&game), "failed loading {extension}");
        if let Some(previous) = &previous_state {
            let previous: &Vec<u8> = previous;
            assert!(
                !retro_unserialize(previous.as_ptr().cast(), previous.len()),
                "reject another platform state"
            );
        }
        VIDEO.store(0, Ordering::Relaxed);
        AUDIO.store(0, Ordering::Relaxed);
        COLOR.store(0, Ordering::Relaxed);
        SIGNAL.store(0, Ordering::Relaxed);
        for _ in 0..60 {
            retro_run();
        }
        assert_eq!(VIDEO.load(Ordering::Relaxed), 60, "{extension}");
        assert!(AUDIO.load(Ordering::Relaxed) > 30_000, "{extension}");
        assert!(COLOR.load(Ordering::Relaxed) > 0, "visible synthetic video for {extension}");
        assert!(SIGNAL.load(Ordering::Relaxed) > 0, "audible synthetic PSG tone for {extension}");
        let mut state = vec![0; retro_serialize_size()];
        assert!(state.len() > 1000);
        assert!(retro_serialize(state.as_mut_ptr().cast(), state.len()));
        retro_run();
        assert!(retro_unserialize(state.as_ptr().cast(), state.len()));
        let mut restored = vec![0; retro_serialize_size()];
        assert!(retro_serialize(restored.as_mut_ptr().cast(), restored.len()));
        assert_eq!(restored, state, "state roundtrip for {extension}");
        previous_state = Some(state);
        assert!(!retro_unserialize(b"invalid".as_ptr().cast(), 7));
        retro_reset();
        retro_run();
        assert!(!jgenesis_game_title().is_null());
        retro_unload_game();
    }
    unsafe extern "C" fn cross(_: u32, _: u32, _: u32, id: u32) -> i16 {
        i16::from(id == RETRO_DEVICE_ID_JOYPAD_B)
    }
    let inputs = read_genesis_inputs(cross);
    let GenesisController::SixButton(p1) = inputs.p1 else { panic!("six-button pad") };
    assert!(p1.b);
    assert!(!p1.a && !p1.c);
    assert!(read_smsgg_inputs(cross).p1.button1);
    exercise_sega_cd(&directory);
    retro_deinit();
    std::fs::remove_dir_all(directory).unwrap();
}

fn exercise_sega_cd(directory: &Path) {
    let cue = directory.join("disc.cue");
    std::fs::write(&cue, "FILE \"disc.bin\" BINARY\n TRACK 01 MODE1/2048\n INDEX 01 00:00:00\n")
        .unwrap();
    let mut sectors = vec![0; 2048 * 16];
    sectors[..14].copy_from_slice(b"SEGADISCSYSTEM");
    std::fs::write(directory.join("disc.bin"), sectors).unwrap();
    let path = CString::new(cue.to_str().unwrap()).unwrap();
    let game = retro_game_info {
        path: path.as_ptr(),
        data: std::ptr::null(),
        size: 0,
        meta: std::ptr::null(),
    };
    assert!(!retro_load_game(&game));
    assert!(unsafe { CStr::from_ptr(jgenesis_boot_error()) }.to_string_lossy().contains("BIOS"));
    let mut bios = genesis_rom();
    bios.resize(128 * 1024, 0);
    // BIOS uses only STOP; no Sega firmware or retail content.
    bios[0x200..0x206].copy_from_slice(&[0x4e, 0x72, 0x27, 0x00, 0x60, 0xfa]);
    std::fs::write(directory.join("bios_CD_U.bin"), &bios).unwrap();
    assert!(retro_load_game(&game), "synthetic BIOS and disc load");
    VIDEO.store(0, Ordering::Relaxed);
    AUDIO.store(0, Ordering::Relaxed);
    for _ in 0..60 {
        retro_run();
    }
    assert_eq!(VIDEO.load(Ordering::Relaxed), 60);
    assert!(AUDIO.load(Ordering::Relaxed) > 30_000);
    let mut state = vec![0; retro_serialize_size()];
    assert!(retro_serialize(state.as_mut_ptr().cast(), state.len()));
    assert!(retro_unserialize(state.as_ptr().cast(), state.len()));
    let disk = DISK.get().unwrap();
    unsafe {
        assert_eq!((disk.get_num_images)(), 1);
        assert!((disk.set_eject_state)(true));
        assert!((disk.get_eject_state)());
        assert!((disk.replace_image_index)(0, &game));
        assert!((disk.set_eject_state)(false));
        assert!(!(disk.get_eject_state)());
        assert!(!(disk.replace_image_index)(1, &game));

        // Match native disc_control.h: append, populate, select, then close.
        let second_cue = directory.join("disc2.cue");
        std::fs::copy(directory.join("disc.bin"), directory.join("disc2.bin")).unwrap();
        std::fs::write(
            &second_cue,
            "FILE \"disc2.bin\" BINARY\n TRACK 01 MODE1/2048\n INDEX 01 00:00:00\n",
        )
        .unwrap();
        let second_path = CString::new(second_cue.to_str().unwrap()).unwrap();
        let second_game = retro_game_info {
            path: second_path.as_ptr(),
            data: std::ptr::null(),
            size: 0,
            meta: std::ptr::null(),
        };
        assert!((disk.set_eject_state)(true));
        assert!((disk.add_image_index)(), "native add-image step");
        assert_eq!((disk.get_num_images)(), 2);
        assert!((disk.replace_image_index)(1, &second_game));
        assert!((disk.set_image_index)(1));
        assert!((disk.set_eject_state)(false));
        assert_eq!((disk.get_image_index)(), 1);

        // Failed replacement must allow the native rollback to remove its slot.
        assert!((disk.set_eject_state)(true));
        assert!((disk.add_image_index)());
        let missing = CString::new(directory.join("missing.chd").to_str().unwrap()).unwrap();
        let bad_game = retro_game_info {
            path: missing.as_ptr(),
            data: std::ptr::null(),
            size: 0,
            meta: std::ptr::null(),
        };
        assert!(!(disk.replace_image_index)(2, &bad_game));
        assert!((disk.set_eject_state)(true));
        assert!((disk.set_image_index)(1));
        assert!((disk.replace_image_index)(2, std::ptr::null()));
        assert_eq!((disk.get_num_images)(), 2);
        assert!((disk.set_eject_state)(false));
        assert_eq!((disk.get_image_index)(), 1);
        assert!(!(disk.get_eject_state)());
        SUPPORT_DISK_EXT.store(true, Ordering::Relaxed);
        retro_set_environment(Some(test_environment));
        let ext = DISK_EXT.get().expect("extended disk interface negotiated");
        let mut image_path = vec![0i8; 1024];
        assert!((ext.get_image_path)(1, image_path.as_mut_ptr(), image_path.len()));
        assert_eq!(CStr::from_ptr(image_path.as_ptr()), second_path.as_c_str());
        let mut too_short = [1i8; 2];
        assert!(!(ext.get_image_path)(1, too_short.as_mut_ptr(), too_short.len()));
        assert_eq!(too_short[0], 0);
        // Reuse the existing first image, as native ChangeDisc does after path lookup.
        assert!((ext.base.set_eject_state)(true));
        assert!((ext.base.set_image_index)(0));
        assert!((ext.base.set_eject_state)(false));
        assert_eq!((ext.base.get_num_images)(), 2);
    }
    retro_run();
    retro_unload_game();
}

#[cfg(not(debug_assertions))]
#[test]
fn optimized_32x_fits_android_sized_stack() {
    std::thread::Builder::new()
        .stack_size(1024 * 1024)
        .spawn(|| {
            let directory = std::env::temp_dir().join("jgenesis-stack-check");
            let mut writer = FsSaveWriter::new(directory.clone(), "32x".into());
            let emulator = GenesisEmulator::create_with_32x_boot_roms(
                GenesisHardware::Sega32X,
                Some(genesis_rom()),
                None,
                None,
                GenesisEmulatorConfig::default(),
                &mut writer,
                Some(s32x_core::BootRoms::new([0; 256], [0; 2048], [0; 1024])),
            )
            .unwrap();
            let mut core = Core::new(
                Emulator::Genesis(emulator),
                writer,
                Console::Sega32X,
                directory.join("32x.32x"),
            );
            core.run_frame(Inputs::Genesis(GenesisInputs::default())).unwrap();
            core.ensure_state_cache().unwrap();
            let state = core.state_cache.clone();
            core.load_state(&state).unwrap();
            drop(core);
            std::fs::remove_dir_all(directory).unwrap();
        })
        .unwrap()
        .join()
        .unwrap();
}

#[test]
fn battery_ram_is_flushed_when_session_ends_before_periodic_save() {
    let directory =
        std::env::temp_dir().join(format!("jgenesis-early-save-{}", std::process::id()));
    let mut writer = FsSaveWriter::new(directory.clone(), "sms".into());
    let mut rom = vec![0; 32768];
    // Enable mapper SRAM, write a byte and loop forever.
    let program = [0xf3, 0x3e, 0x08, 0x32, 0xfc, 0xff, 0x3e, 0x5a, 0x32, 0x00, 0x80, 0x18, 0xfe];
    rom[..program.len()].copy_from_slice(&program);
    let emulator = SmsGgEmulator::create(
        Some(rom),
        None,
        SmsGgHardware::MasterSystem,
        smsgg_config(),
        &mut writer,
    );
    let mut core = Core::new(
        Emulator::SmsGg(emulator),
        writer,
        Console::MasterSystem,
        directory.join("sms.sms"),
    );
    core.run_frame(Inputs::SmsGg(SmsGgInputs::default())).unwrap();
    drop(core);
    assert_eq!(std::fs::read(directory.join("sms.sav")).unwrap()[0], 0x5a);
    std::fs::remove_dir_all(directory).unwrap();
}

// Programs below are authored here: they write RAM, then continuously read it
// into the displayed backdrop. Pixel changes prove CPU bus reads see the cheat.
#[test]
fn cheats_execute_on_genesis_ram_rom_and_survive_state_and_option_reload() {
    std::thread::Builder::new().stack_size(32 * 1024 * 1024)
        .spawn(exercise_genesis_cheats).unwrap().join().unwrap();
}

fn exercise_genesis_cheats() {
    for (hardware, console) in [(GenesisHardware::Standalone, Console::Genesis),
        (GenesisHardware::SegaCd, Console::SegaCd), (GenesisHardware::Sega32X, Console::Sega32X)] {
    let directory = std::env::temp_dir().join(format!("jgenesis-md-cheats-{:?}-{}", console, std::process::id()));
    let mut rom = genesis_rom();
    let program: &[u8] = &[
        0x33,0xfc,0x00,0x0e,0x00,0xff,0x00,0x00, // RAM = red
        0x33,0xfc,0x81,0x44,0x00,0xc0,0x00,0x04, // display on
        0x23,0xfc,0xc0,0x00,0x00,0x00,0x00,0xc0,0x00,0x04, // CRAM address
        0x33,0xf9,0x00,0xff,0x00,0x00,0x00,0xc0,0x00,0x00, // RAM -> CRAM
        0x60,0xea, // repeat CRAM writes
    ];
    rom[0x200..0x200+program.len()].copy_from_slice(program);
    let mut writer = FsSaveWriter::new(directory.clone(), "md".into());
    let bios = hardware.has_sega_cd().then(|| vec![0; 128 * 1024]);
    let boot_roms = hardware.has_32x().then(|| s32x_core::BootRoms::new([0; 256], [0; 2048], [0; 1024]));
    let emulator = GenesisEmulator::create_with_32x_boot_roms(hardware, Some(rom), bios, None,
        GenesisEmulatorConfig::default(), &mut writer, boot_roms).unwrap();
    let mut core = Core::new(Emulator::Genesis(emulator), writer, console, directory.join("test.md"));
    let render = |core: &mut Core| {
        for _ in 0..2 { core.run_frame(Inputs::Genesis(GenesisInputs::default())).unwrap(); }
        core.frame.pixels.clone()
    };
    let baseline = render(&mut core);
    core.set_cheat(0, true, "FF0000:00E0");
    let cheated = render(&mut core);
    assert_ne!(baseline, cheated, "68k RAM override changes rendered color");
    core.ensure_state_cache().unwrap();
    let state = core.state_cache.clone();
    core.set_cheat(0, false, "");
    assert_eq!(render(&mut core), baseline);
    core.load_state(&state).unwrap();
    assert_eq!(render(&mut core), baseline, "loaded state cannot re-enable an old cheat");
    core.set_cheat(1, true, "6ABA-AAAC"); // patch immediate RAM initialization in ROM
    core.hard_reset();
    assert_eq!(render(&mut core), cheated, "ROM override is executed after reset");
    core.set_cheat(1, false, "");
    core.hard_reset();
    assert_eq!(render(&mut core), baseline);
    drop(core);
    std::fs::remove_dir_all(directory).unwrap();
    }
}

#[test]
fn cheats_execute_on_sms_gamegear_and_sg1000_ram_and_disable_cleanly() {
    for (console, hardware) in [(Console::MasterSystem, SmsGgHardware::MasterSystem),
        (Console::GameGear, SmsGgHardware::GameGear), (Console::Sg1000, SmsGgHardware::Sg1000)] {
        let directory = std::env::temp_dir().join(format!("jgenesis-8bit-cheats-{:?}-{}", console, std::process::id()));
        let mut rom = smsgg_rom();
        let program = [0xf3,0x3e,0x02,0x32,0x00,0xc0, // RAM = color 2
            0x3e,0x00,0xd3,0xbf,0x3e,0x80,0xd3,0xbf, // TMS mode
            0x3e,0x40,0xd3,0xbf,0x3e,0x81,0xd3,0xbf, // display on
            0x3a,0x00,0xc0,0xd3,0xbf,0x3e,0x87,0xd3,0xbf,0xc3,0x16,0x00];
        rom[..program.len()].copy_from_slice(&program);
        let mut writer = FsSaveWriter::new(directory.clone(), "8bit".into());
        let emulator = SmsGgEmulator::create(Some(rom), None, hardware, smsgg_config(), &mut writer);
        let mut core = Core::new(Emulator::SmsGg(emulator), writer, console, directory.join("test.sms"));
        let render = |core: &mut Core| {
            for _ in 0..2 { core.run_frame(Inputs::SmsGg(SmsGgInputs::default())).unwrap(); }
            core.frame.pixels.clone()
        };
        let baseline = render(&mut core);
        core.set_cheat(0, true, "00C00004");
        let cheated = render(&mut core);
        assert!(baseline != cheated, "{:?} RAM override changes display", console);
        core.apply_cheats(); // same path as frontend option reload
        assert_eq!(render(&mut core), cheated);
        core.set_cheat(0, false, "");
        assert_eq!(render(&mut core), baseline);
        core.set_cheat(1, true, "040-003"); // Game Genie for same C000 byte
        assert_eq!(render(&mut core), cheated, "Game Genie reaches the CPU bus");
        core.set_cheat(1, false, "");
        assert_eq!(render(&mut core), baseline);
        drop(core);
        std::fs::remove_dir_all(directory).unwrap();
    }
}
