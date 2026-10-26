mod cheats;
mod content;
mod diagnostics;
mod disk;
mod frontend;
mod libretro;
mod options;
mod smsgg_compat;
#[cfg(test)]
mod tests;

use cdrom::reader::{CdRom, CdRomFileFormat};
use content::{
    Console, base_name_for_saves, detect_cartridge_console, is_disc_path, read_bios_file,
    read_cartridge, sega_cd_bios_path,
};
use frontend::{AudioBuffer, FrameOutput, FsSaveWriter};
use genesis_config::{
    GenesisButton, GenesisController, GenesisControllerType, GenesisEmulatorConfig, GenesisInputs,
    GenesisRegion,
};
use genesis_core::GenesisEmulator;
use genesis_core::api::GenesisHardware;
use jgenesis_common::frontend::{
    ConstantInputPoller, EmulatorTrait, InputPoller, MappableInputs, TickEffect,
};
use jgenesis_common::input::Player;
use libretro::*;
use smsgg_config::{GgAspectRatio, SmsAspectRatio, SmsGgButton, SmsGgInputs, SmsModel};
use smsgg_core::{SmsGgEmulator, SmsGgEmulatorConfig, SmsGgHardware};
use std::ffi::{CStr, CString};
use std::num::NonZeroU32;
use std::os::raw::{c_char, c_void};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

const AUDIO_SAMPLE_RATE: u64 = 44_100;
const MAX_FRAME_ITERATIONS: u32 = 2_000_000;

static ENV: OnceLock<Mutex<Environment>> = OnceLock::new();
static CORE: OnceLock<Mutex<Option<Core>>> = OnceLock::new();
static BOOT_ERROR: OnceLock<Mutex<Option<CString>>> = OnceLock::new();

fn environment() -> &'static Mutex<Environment> {
    ENV.get_or_init(|| Mutex::new(Environment::new()))
}

fn core_slot() -> &'static Mutex<Option<Core>> {
    CORE.get_or_init(|| Mutex::new(None))
}

fn boot_error_slot() -> &'static Mutex<Option<CString>> {
    BOOT_ERROR.get_or_init(|| Mutex::new(None))
}

fn lock<T>(mutex: &'static Mutex<T>) -> std::sync::MutexGuard<'static, T> {
    mutex.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

struct Environment {
    frontend: Option<retro_environment_t>,
    video: Option<retro_video_refresh_t>,
    audio_batch: Option<retro_audio_sample_batch_t>,
    audio_sample: Option<retro_audio_sample_t>,
    input_poll: Option<retro_input_poll_t>,
    input_state: Option<retro_input_state_t>,
    system_dir: Option<CString>,
    save_dir: Option<CString>,
    assets_dir: Option<CString>,
}

impl Environment {
    fn new() -> Self {
        Self {
            frontend: None,
            video: None,
            audio_batch: None,
            audio_sample: None,
            input_poll: None,
            input_state: None,
            system_dir: None,
            save_dir: None,
            assets_dir: None,
        }
    }

    fn query_directory(&self, cmd: u32) -> Option<PathBuf> {
        let frontend = self.frontend?;
        let mut pointer: *const c_char = std::ptr::null();
        let handled =
            unsafe { frontend(cmd, (&mut pointer as *mut *const c_char).cast::<c_void>()) };
        if !handled || pointer.is_null() {
            return None;
        }
        let value = unsafe { CStr::from_ptr(pointer) }.to_string_lossy().into_owned();
        if value.is_empty() { None } else { Some(PathBuf::from(value)) }
    }
}

fn set_boot_error(message: String) {
    let c_string = CString::new(message)
        .unwrap_or_else(|_| CString::new("Unknown emulator error").expect("literal CString"));
    *lock(boot_error_slot()) = Some(c_string);
}

fn set_fatal_boot_error(message: String) {
    diagnostics::report_unrecoverable_error(&message);
    set_boot_error(message);
}

fn clear_boot_error() {
    diagnostics::reset_error_report();
    *lock(boot_error_slot()) = None;
}

#[cfg(all(target_os = "android", debug_assertions))]
#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(priority: i32, tag: *const c_char, text: *const c_char) -> i32;
}

#[cfg(debug_assertions)]
fn core_log_info(message: &str) {
    #[cfg(target_os = "android")]
    {
        if let Ok(text) = CString::new(message) {
            unsafe {
                __android_log_write(
                    4,
                    b"jgenesis_libretro\0".as_ptr().cast::<c_char>(),
                    text.as_ptr(),
                );
            }
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        eprintln!("[jgenesis_libretro] {message}");
    }
}

macro_rules! core_log_info {
    ($($argument:tt)*) => {
        #[cfg(debug_assertions)]
        { core_log_info(&format!($($argument)*)); }
    };
}

enum Emulator {
    Genesis(GenesisEmulator),
    SmsGg(SmsGgEmulator),
}

struct Core {
    emulator: Emulator,
    save_writer: FsSaveWriter,
    frame: FrameOutput,
    audio: AudioBuffer,
    av: retro_system_av_info,
    state_cache: Vec<u8>,
    console: Console,
    content_path: PathBuf,
    title: CString,
    disc_ejected: bool,
    disc_paths: Vec<Option<PathBuf>>,
    disc_index: u32,
    cheats: std::collections::BTreeMap<u32, String>,
}

enum Inputs {
    Genesis(GenesisInputs),
    SmsGg(SmsGgInputs),
}

/// Libretro snapshots inputs once per retro_run. Deliver that snapshot on the
/// first emulation tick, while leaving controller I/O and timers running normally.
struct FrameInputPoller<'a, Inputs> {
    inputs: &'a Inputs,
    delivered: bool,
}

impl<'a, Inputs> FrameInputPoller<'a, Inputs> {
    fn new(inputs: &'a Inputs) -> Self {
        Self { inputs, delivered: false }
    }
}

impl<Inputs> InputPoller<Inputs> for FrameInputPoller<'_, Inputs> {
    fn poll(&mut self) -> &Inputs {
        self.inputs
    }

    fn poll_if_changed(&mut self) -> Option<&Inputs> {
        if self.delivered {
            return None;
        }
        self.delivered = true;
        Some(self.inputs)
    }
}

impl Drop for Core {
    fn drop(&mut self) {
        let result = match &mut self.emulator {
            Emulator::Genesis(emulator) => emulator.persist_save_files(&mut self.save_writer),
            Emulator::SmsGg(emulator) => emulator.persist_save_files(&mut self.save_writer),
        };
        if let Err(error) = result {
            set_boot_error(format!("Unable to flush battery saves: {error}"));
        }
    }
}

impl Core {
    fn new(
        mut emulator: Emulator,
        save_writer: FsSaveWriter,
        console: Console,
        content_path: PathBuf,
    ) -> Self {
        let geometry_console = match &emulator {
            Emulator::SmsGg(value)
                if console == Console::GameGear
                    && value.hardware() == SmsGgHardware::MasterSystem =>
            {
                Console::MasterSystem
            }
            _ => console,
        };
        let (base_width, base_height, max_width, max_height, aspect_ratio) =
            geometry_console.default_geometry();
        let fps = match &emulator {
            Emulator::Genesis(value) => value.target_fps(),
            Emulator::SmsGg(value) => value.target_fps(),
        };
        let title = match &mut emulator {
            Emulator::Genesis(value) => value.game_title(),
            Emulator::SmsGg(_) => None,
        }
        .filter(|value| !value.trim_matches('\0').trim().is_empty())
        .unwrap_or_else(|| {
            content_path.file_stem().unwrap_or_default().to_string_lossy().into_owned()
        });
        let title = CString::new(title.replace('\0', "")).unwrap_or_default();
        let disc_paths = if matches!(console, Console::SegaCd | Console::SegaCd32X) {
            vec![Some(content_path.clone())]
        } else {
            Vec::new()
        };
        Self {
            emulator,
            save_writer,
            frame: FrameOutput::new(),
            audio: AudioBuffer::new(),
            av: retro_system_av_info {
                geometry: retro_game_geometry {
                    base_width,
                    base_height,
                    max_width,
                    max_height,
                    aspect_ratio,
                },
                timing: retro_system_timing {
                    fps: if fps.is_finite() && fps > 1.0 { fps } else { 60.0 },
                    sample_rate: AUDIO_SAMPLE_RATE as f64,
                },
            },
            state_cache: Vec::new(),
            console,
            content_path,
            title,
            disc_ejected: false,
            disc_paths,
            disc_index: 0,
            cheats: Default::default(),
        }
    }

    fn apply_cheats(&mut self) {
        match &mut self.emulator {
            Emulator::Genesis(emulator) => {
                let mut config = options::genesis();
                config.cheat_codes = cheats::genesis_codes(self.cheats.values());
                emulator.reload_config(&config);
            }
            Emulator::SmsGg(emulator) => {
                let mut config = options::smsgg(self.console);
                config.cheat_codes = cheats::smsgg_codes(self.cheats.values());
                emulator.reload_config(&config);
            }
        }
        self.state_cache.clear();
    }

    fn set_cheat(&mut self, index: u32, enabled: bool, code: &str) {
        self.cheats.remove(&index);
        if enabled {
            self.cheats.insert(index, code.to_owned());
        }
        self.apply_cheats();
    }

    fn run_frame(&mut self, inputs: Inputs) -> Result<(), String> {
        self.frame.begin_frame();
        self.audio.begin_frame();
        self.state_cache.clear();

        let mut genesis_poller = match &inputs {
            Inputs::Genesis(inputs) => Some(FrameInputPoller::new(inputs)),
            Inputs::SmsGg(_) => None,
        };
        let mut iterations = 0u32;
        loop {
            iterations = iterations.saturating_add(1);
            if iterations > MAX_FRAME_ITERATIONS {
                return Err("emulator did not finish a frame in time".to_string());
            }

            let effect = match (&mut self.emulator, &inputs, &mut genesis_poller) {
                (Emulator::Genesis(emulator), Inputs::Genesis(_), Some(poller)) => {
                    #[cfg(feature = "jit-runtime")]
                    let result = emulator.tick_native_batch(
                        &mut self.frame,
                        &mut self.audio,
                        poller,
                        &mut self.save_writer,
                    );
                    #[cfg(not(feature = "jit-runtime"))]
                    let result = emulator.tick(
                        &mut self.frame,
                        &mut self.audio,
                        poller,
                        &mut self.save_writer,
                    );
                    result.map_err(|error| error.to_string())
                }
                (Emulator::SmsGg(emulator), Inputs::SmsGg(inputs), _) => {
                    let mut poller = ConstantInputPoller(inputs);
                    emulator
                        .tick(&mut self.frame, &mut self.audio, &mut poller, &mut self.save_writer)
                        .map_err(|error| error.to_string())
                }
                _ => return Err("input type does not match emulator".to_string()),
            }?;

            if effect == TickEffect::FrameRendered {
                break;
            }
        }

        Ok(())
    }

    fn hard_reset(&mut self) {
        match &mut self.emulator {
            Emulator::Genesis(emulator) => emulator.hard_reset(&mut self.save_writer),
            Emulator::SmsGg(emulator) => emulator.hard_reset(&mut self.save_writer),
        }
        self.state_cache.clear();
    }

    fn ensure_state_cache(&mut self) -> Result<(), String> {
        if !self.state_cache.is_empty() {
            return Ok(());
        }
        let encoded = match &self.emulator {
            Emulator::Genesis(emulator) => encode_state(emulator),
            Emulator::SmsGg(emulator) => encode_state(emulator),
        }?;
        self.state_cache = b"JGLRSTA1".to_vec();
        self.state_cache.push(self.console as u8);
        self.state_cache.extend_from_slice(&encoded);
        Ok(())
    }

    fn load_state(&mut self, bytes: &[u8]) -> Result<(), String> {
        if bytes.len() < 9 || &bytes[..8] != b"JGLRSTA1" || bytes[8] != self.console as u8 {
            return Err(
                "Save state belongs to another platform or incompatible shim version".into()
            );
        }
        let bytes = &bytes[9..];
        match &mut self.emulator {
            Emulator::Genesis(emulator) => decode_state(emulator, bytes),
            Emulator::SmsGg(emulator) => {
                let (state, _) =
                    bincode::decode_from_slice::<SmsGgEmulator, _>(bytes, bincode_config())
                        .map_err(|error| format!("Unable to deserialize save state: {error}"))?;
                if state.hardware() != emulator.hardware() {
                    return Err(
                        "Save state uses incompatible SMS/Game Gear cartridge hardware mode".into(),
                    );
                }
                emulator.load_state(state);
                Ok(())
            }
        }?;
        self.apply_cheats();
        Ok(())
    }
}

fn bincode_config() -> impl bincode::config::Config {
    bincode::config::standard()
        .with_little_endian()
        .with_fixed_int_encoding()
        .with_limit::<{ 100 * 1024 * 1024 }>()
}

fn encode_state<E: EmulatorTrait>(emulator: &E) -> Result<Vec<u8>, String> {
    let state = emulator.to_save_state();
    bincode::encode_to_vec(&state, bincode_config())
        .map_err(|error| format!("Unable to serialize save state: {error}"))
}

fn decode_state<E: EmulatorTrait>(emulator: &mut E, bytes: &[u8]) -> Result<(), String> {
    let (state, _) = bincode::decode_from_slice::<E::SaveState, _>(bytes, bincode_config())
        .map_err(|error| format!("Unable to deserialize save state: {error}"))?;
    emulator.load_state(state);
    Ok(())
}

#[cfg(target_os = "android")]
fn set_pixel_format() -> bool {
    let mut format = RETRO_PIXEL_FORMAT_XRGB8888 as i32;
    let environment = environment();
    let guard = lock(environment);
    let Some(frontend) = guard.frontend else {
        return false;
    };
    unsafe {
        frontend(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, (&mut format as *mut i32).cast::<c_void>())
    }
}

#[cfg(not(target_os = "android"))]
fn set_pixel_format() -> bool {
    let mut format = RETRO_PIXEL_FORMAT_XRGB8888 as i32;
    let environment = environment();
    let guard = lock(environment);
    let Some(frontend) = guard.frontend else {
        return false;
    };
    unsafe {
        frontend(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, (&mut format as *mut i32).cast::<c_void>())
    }
}

fn query_directory(cmd: u32) -> Option<PathBuf> {
    let environment = environment();
    let guard = lock(environment);
    guard.query_directory(cmd)
}

fn store_directories() {
    let system_dir = query_directory(RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY)
        .unwrap_or_else(|| PathBuf::from("."));
    let save_dir =
        query_directory(RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY).unwrap_or_else(|| system_dir.clone());
    let assets_dir = query_directory(RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY)
        .unwrap_or_else(|| system_dir.clone());

    let to_c_string = |path: &Path| CString::new(path.to_string_lossy().as_bytes()).ok();
    let environment = environment();
    let mut guard = lock(environment);
    guard.system_dir = to_c_string(&system_dir);
    guard.save_dir = to_c_string(&save_dir);
    guard.assets_dir = to_c_string(&assets_dir);

    let _ = std::fs::create_dir_all(&system_dir);
    let _ = std::fs::create_dir_all(&save_dir);
    let _ = std::fs::create_dir_all(&assets_dir);
}

fn read_genesis_inputs(input_state: retro_input_state_t) -> GenesisInputs {
    let mut inputs = GenesisInputs {
        p1: GenesisController::new(GenesisControllerType::SixButton),
        p2: GenesisController::new(GenesisControllerType::SixButton),
    };

    let pressed =
        |port: u32, id: u32| unsafe { input_state(port, RETRO_DEVICE_JOYPAD, 0, id) != 0 };

    for (player, port) in [(Player::One, 0u32), (Player::Two, 1u32)] {
        let mut set = |button: GenesisButton, id: u32| {
            if pressed(port, id) {
                inputs.set_field(button, player, true);
            }
        };
        set(GenesisButton::Up, RETRO_DEVICE_ID_JOYPAD_UP);
        set(GenesisButton::Down, RETRO_DEVICE_ID_JOYPAD_DOWN);
        set(GenesisButton::Left, RETRO_DEVICE_ID_JOYPAD_LEFT);
        set(GenesisButton::Right, RETRO_DEVICE_ID_JOYPAD_RIGHT);
        set(GenesisButton::A, RETRO_DEVICE_ID_JOYPAD_Y);
        set(GenesisButton::B, RETRO_DEVICE_ID_JOYPAD_B);
        set(GenesisButton::C, RETRO_DEVICE_ID_JOYPAD_A);
        set(GenesisButton::X, RETRO_DEVICE_ID_JOYPAD_L);
        set(GenesisButton::Y, RETRO_DEVICE_ID_JOYPAD_X);
        set(GenesisButton::Z, RETRO_DEVICE_ID_JOYPAD_R);
        set(GenesisButton::Start, RETRO_DEVICE_ID_JOYPAD_START);
        set(GenesisButton::Mode, RETRO_DEVICE_ID_JOYPAD_SELECT);
    }

    inputs
}

fn read_smsgg_inputs(input_state: retro_input_state_t) -> SmsGgInputs {
    let mut inputs = SmsGgInputs::default();
    let pressed =
        |port: u32, id: u32| unsafe { input_state(port, RETRO_DEVICE_JOYPAD, 0, id) != 0 };

    for (player, port) in [(Player::One, 0u32), (Player::Two, 1u32)] {
        let mut set = |button: SmsGgButton, id: u32| {
            if pressed(port, id) {
                inputs.set_field(button, player, true);
            }
        };
        set(SmsGgButton::Up, RETRO_DEVICE_ID_JOYPAD_UP);
        set(SmsGgButton::Down, RETRO_DEVICE_ID_JOYPAD_DOWN);
        set(SmsGgButton::Left, RETRO_DEVICE_ID_JOYPAD_LEFT);
        set(SmsGgButton::Right, RETRO_DEVICE_ID_JOYPAD_RIGHT);
        set(SmsGgButton::Button1, RETRO_DEVICE_ID_JOYPAD_B);
        set(SmsGgButton::Button2, RETRO_DEVICE_ID_JOYPAD_A);
        set(SmsGgButton::Button1, RETRO_DEVICE_ID_JOYPAD_Y);
        set(SmsGgButton::Button2, RETRO_DEVICE_ID_JOYPAD_X);
    }

    if pressed(0, RETRO_DEVICE_ID_JOYPAD_START) {
        inputs.set_field(SmsGgButton::Pause, Player::One, true);
    }

    inputs
}

fn smsgg_config() -> SmsGgEmulatorConfig {
    SmsGgEmulatorConfig {
        sms_timing_mode: jgenesis_common::frontend::TimingMode::Ntsc,
        sms_model: SmsModel::Sms2,
        forced_psg_version: None,
        sms_aspect_ratio: SmsAspectRatio::Ntsc,
        gg_aspect_ratio: GgAspectRatio::GgLcd,
        remove_sprite_limit: false,
        forced_region: None,
        sms_crop_vertical_border: true,
        sms_crop_left_border: false,
        gg_frame_blending: false,
        gg_use_sms_resolution: false,
        fm_sound_unit_enabled: true,
        z80_divider: NonZeroU32::new(smsgg_core::NATIVE_Z80_DIVIDER)
            .unwrap_or_else(|| NonZeroU32::new(15).expect("non-zero")),
        allow_opposing_joypad_directions: false,
        cheat_codes: Vec::new(),
    }
}

fn format_from_path(path: &Path) -> Option<CdRomFileFormat> {
    CdRomFileFormat::from_file_path(path)
}

fn load_core(path: &Path) -> Result<Core, String> {
    if !set_pixel_format() {
        return Err("Frontend rejected XRGB8888 pixel format".to_string());
    }
    store_directories();

    let system_dir = {
        let environment = environment();
        let guard = lock(environment);
        guard
            .system_dir
            .as_ref()
            .map(|value| PathBuf::from(value.to_string_lossy().into_owned()))
            .unwrap_or_else(|| PathBuf::from("."))
    };
    let save_dir = {
        let environment = environment();
        let guard = lock(environment);
        guard
            .save_dir
            .as_ref()
            .map(|value| PathBuf::from(value.to_string_lossy().into_owned()))
            .unwrap_or_else(|| system_dir.clone())
    };

    let base_name = options::save_id().unwrap_or_else(|| base_name_for_saves(path));
    let mut save_writer = FsSaveWriter::new(save_dir, base_name);

    if is_disc_path(path) {
        return load_sega_cd(path, system_dir, save_writer);
    }

    let (bytes, extension) = read_cartridge(path)?;
    let console = detect_cartridge_console(&extension, &bytes);

    let emulator = match console {
        Console::MasterSystem | Console::GameGear | Console::Sg1000 => {
            let hardware = match console {
                Console::MasterSystem => SmsGgHardware::MasterSystem,
                Console::GameGear if smsgg_compat::game_gear_uses_sms_mode(&bytes) => {
                    core_log_info!("Game Gear cartridge requests SMS compatibility mode (pin 42)");
                    SmsGgHardware::MasterSystem
                }
                Console::GameGear => SmsGgHardware::GameGear,
                Console::Sg1000 => SmsGgHardware::Sg1000,
                _ => unreachable!(),
            };
            let bios = None;
            let mut emulator = SmsGgEmulator::create(
                Some(bytes),
                bios,
                hardware,
                options::smsgg(console),
                &mut save_writer,
            );
            emulator.update_audio_output_frequency(AUDIO_SAMPLE_RATE);
            Emulator::SmsGg(emulator)
        }
        Console::Genesis | Console::Sega32X => {
            let hardware = if console == Console::Sega32X {
                GenesisHardware::Sega32X
            } else {
                GenesisHardware::Standalone
            };
            let config = options::genesis();
            let boot_roms = if hardware.has_32x() {
                Some(s32x_core::BootRoms::load(&system_dir)?)
            } else {
                None
            };
            let mut emulator = GenesisEmulator::create_with_32x_boot_roms(
                hardware,
                Some(bytes),
                None,
                None,
                config.clone(),
                &mut save_writer,
                boot_roms,
            )
            .map_err(|error| format!("Unable to create Genesis emulator: {error}"))?;
            emulator.update_audio_output_frequency(AUDIO_SAMPLE_RATE);
            Emulator::Genesis(emulator)
        }
        Console::SegaCd | Console::SegaCd32X => unreachable!("disc handled above"),
    };

    #[cfg(debug_assertions)]
    let mut emulator = emulator;
    #[cfg(debug_assertions)]
    let title = match &mut emulator {
        Emulator::Genesis(value) => {
            value.game_title().map(|value| value.to_string()).unwrap_or_default()
        }
        Emulator::SmsGg(_) => String::new(),
    };
    core_log_info!("Loaded {} content ({}), title='{}'", console.label(), path.display(), title);

    Ok(Core::new(emulator, save_writer, console, path.to_path_buf()))
}

fn load_sega_cd(
    path: &Path,
    system_dir: PathBuf,
    mut save_writer: FsSaveWriter,
) -> Result<Core, String> {
    let format = format_from_path(path)
        .ok_or_else(|| format!("Unsupported Sega CD image format: {}", path.display()))?;
    let mut disc = CdRom::open(path, format)
        .map_err(|error| format!("Unable to open Sega CD image {}: {error}", path.display()))?;

    let config = options::genesis();
    let region = config.forced_region.unwrap_or_else(|| {
        segacd_core::parse_disc_region(&mut disc).unwrap_or(GenesisRegion::Americas)
    });
    let bios_path = sega_cd_bios_path(&system_dir, region).ok_or_else(|| {
        format!(
            "Sega CD BIOS not found (128 KiB, {}). Put bios_CD_U.bin / bios_CD_E.bin / bios_CD_J.bin in {}",
            region.short_name(),
            system_dir.display()
        )
    })?;
    let bios = read_bios_file(&bios_path)?;
    if bios.len() != 128 * 1024 {
        return Err(format!(
            "Sega CD BIOS {} is {} bytes, expected 131072",
            bios_path.display(),
            bios.len()
        ));
    }

    let is_32x = segacd_core::is_cd_32x_disc(&mut disc);
    let console = if is_32x { Console::SegaCd32X } else { Console::SegaCd };
    let hardware = if is_32x { GenesisHardware::SegaCd32X } else { GenesisHardware::SegaCd };
    let boot_roms =
        if hardware.has_32x() { Some(s32x_core::BootRoms::load(&system_dir)?) } else { None };
    let mut emulator = GenesisEmulator::create_with_32x_boot_roms(
        hardware,
        None,
        Some(bios),
        Some(disc),
        config,
        &mut save_writer,
        boot_roms,
    )
    .map_err(|error| format!("Unable to create Sega CD emulator: {error}"))?;
    emulator.update_audio_output_frequency(AUDIO_SAMPLE_RATE);

    #[cfg(debug_assertions)]
    let title = emulator.game_title().unwrap_or_default();
    core_log_info!(
        "Loaded {} content ({}), title='{}', BIOS={}",
        console.label(),
        path.display(),
        title,
        bios_path.display()
    );

    Ok(Core::new(Emulator::Genesis(emulator), save_writer, console, path.to_path_buf()))
}

fn current_av_info() -> retro_system_av_info {
    let core = core_slot();
    let guard = lock(core);
    guard.as_ref().map_or_else(
        || retro_system_av_info {
            geometry: retro_game_geometry {
                base_width: 320,
                base_height: 224,
                max_width: 320,
                max_height: 480,
                aspect_ratio: 4.0 / 3.0,
            },
            timing: retro_system_timing { fps: 60.0, sample_rate: AUDIO_SAMPLE_RATE as f64 },
        },
        |core| core.av,
    )
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_api_version() -> u32 {
    RETRO_API_VERSION
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_set_environment(callback: Option<retro_environment_t>) {
    lock(environment()).frontend = callback;
    if let Some(callback) = callback {
        options::register(callback);
        disk::register(callback);
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_set_video_refresh(callback: Option<retro_video_refresh_t>) {
    let environment = environment();
    let mut guard = lock(environment);
    guard.video = callback;
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_set_audio_sample(callback: Option<retro_audio_sample_t>) {
    let environment = environment();
    let mut guard = lock(environment);
    guard.audio_sample = callback;
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_set_audio_sample_batch(callback: Option<retro_audio_sample_batch_t>) {
    let environment = environment();
    let mut guard = lock(environment);
    guard.audio_batch = callback;
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_set_input_poll(callback: Option<retro_input_poll_t>) {
    let environment = environment();
    let mut guard = lock(environment);
    guard.input_poll = callback;
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_set_input_state(callback: Option<retro_input_state_t>) {
    let environment = environment();
    let mut guard = lock(environment);
    guard.input_state = callback;
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_init() {
    diagnostics::reset_error_report();
    diagnostics::install_panic_hook();
    core_log_info!("jgenesis libretro core initialized");
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_deinit() {
    #[cfg(feature = "jit-runtime")]
    jgenesis_common::jit::reset();
    *lock(core_slot()) = None;
    core_log_info!("jgenesis libretro core deinitialized");
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_get_system_info(info: *mut retro_system_info) {
    static LIBRARY_NAME: &[u8] = b"jgenesis\0";
    static LIBRARY_VERSION: &[u8] = b"0.14.1\0";
    static VALID_EXTENSIONS: &[u8] = b"md|gen|bin|smd|32x|cue|chd|sms|gg|sg|zip\0";

    if info.is_null() {
        return;
    }
    unsafe {
        (*info).library_name = LIBRARY_NAME.as_ptr().cast::<c_char>();
        (*info).library_version = LIBRARY_VERSION.as_ptr().cast::<c_char>();
        (*info).valid_extensions = VALID_EXTENSIONS.as_ptr().cast::<c_char>();
        (*info).need_fullpath = true;
        (*info).block_extract = true;
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_get_system_av_info(info: *mut retro_system_av_info) {
    if info.is_null() {
        return;
    }
    unsafe {
        *info = current_av_info();
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_get_region() -> u32 {
    u32::from(current_av_info().timing.fps < 55.0)
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_load_game_special(
    _kind: u32,
    _games: *const retro_game_info,
    _count: usize,
) -> bool {
    false
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_load_game(info: *const retro_game_info) -> bool {
    clear_boot_error();
    #[cfg(feature = "jit-runtime")]
    jgenesis_common::jit::reset();
    *lock(core_slot()) = None;

    if info.is_null() {
        set_fatal_boot_error("retro_load_game received null game info".to_string());
        return false;
    }
    let path_pointer = unsafe { (*info).path };
    if path_pointer.is_null() {
        set_fatal_boot_error("retro_load_game requires a filesystem path".to_string());
        return false;
    }
    let path = unsafe { CStr::from_ptr(path_pointer) }.to_string_lossy().into_owned();
    if path.is_empty() {
        set_fatal_boot_error("retro_load_game received an empty path".to_string());
        return false;
    }

    match load_core(Path::new(&path)) {
        Ok(core) => {
            *lock(core_slot()) = Some(core);
            true
        }
        Err(error) => {
            set_fatal_boot_error(error);
            false
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_unload_game() {
    #[cfg(feature = "jit-runtime")]
    jgenesis_common::jit::reset();
    *lock(core_slot()) = None;
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_reset() {
    #[cfg(feature = "jit-runtime")]
    jgenesis_common::jit::invalidate();
    let core = core_slot();
    let mut guard = lock(core);
    if let Some(core) = guard.as_mut() {
        core.hard_reset();
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_run() {
    #[cfg(feature = "jit-runtime")]
    jgenesis_common::jit::set_enabled(options::jit_backend_native());
    let options_changed = options::changed();
    let environment = environment();
    let (frontend, video, audio_batch, audio_sample, input_poll, input_state) = {
        let guard = lock(environment);
        (
            guard.frontend,
            guard.video,
            guard.audio_batch,
            guard.audio_sample,
            guard.input_poll,
            guard.input_state,
        )
    };

    if let Some(poll) = input_poll {
        unsafe { poll() };
    }

    let core = core_slot();
    let mut guard = lock(core);
    let Some(core) = guard.as_mut() else {
        return;
    };

    if options_changed {
        core.apply_cheats();
    }

    let inputs = match &core.emulator {
        Emulator::Genesis(_) => Inputs::Genesis(match input_state {
            Some(state) => read_genesis_inputs(state),
            None => GenesisInputs::default(),
        }),
        Emulator::SmsGg(_) => Inputs::SmsGg(match input_state {
            Some(state) => read_smsgg_inputs(state),
            None => SmsGgInputs::default(),
        }),
    };

    if let Err(error) = core.run_frame(inputs) {
        set_fatal_boot_error(error);
        guard.take();
        drop(guard);
        if let Some(callback) = frontend {
            unsafe {
                callback(RETRO_ENVIRONMENT_SHUTDOWN, std::ptr::null_mut());
            }
        }
        return;
    }

    if core.frame.queued {
        let width = core.frame.width;
        let height = core.frame.height;
        let aspect = core.frame.aspect_ratio;
        let fps = core.frame.fps;
        let geometry = retro_game_geometry {
            base_width: width,
            base_height: height,
            max_width: core.av.geometry.max_width.max(width),
            max_height: core.av.geometry.max_height.max(height),
            aspect_ratio: aspect as f32,
        };
        let av = retro_system_av_info {
            geometry,
            timing: retro_system_timing { fps, sample_rate: AUDIO_SAMPLE_RATE as f64 },
        };
        let geometry_changed = core.av.geometry.base_width != width
            || core.av.geometry.base_height != height
            || (core.av.geometry.aspect_ratio - geometry.aspect_ratio).abs() > 0.0001;
        core.av = av;
        if geometry_changed && let Some(callback) = frontend {
            let mut geometry = geometry;
            unsafe {
                callback(
                    RETRO_ENVIRONMENT_SET_GEOMETRY,
                    (&mut geometry as *mut retro_game_geometry).cast(),
                );
            }
        }

        if let Some(refresh) = video {
            unsafe {
                refresh(
                    core.frame.pixels.as_ptr().cast::<c_void>(),
                    width,
                    height,
                    width as usize * std::mem::size_of::<u32>(),
                );
            }
        }

        if let Some(batch) = audio_batch {
            if !core.audio.samples.is_empty() {
                unsafe {
                    batch(core.audio.samples.as_ptr(), core.audio.frames());
                }
            }
        } else if let Some(sample) = audio_sample {
            for frame in core.audio.samples.chunks_exact(2) {
                unsafe { sample(frame[0], frame[1]) };
            }
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_serialize_size() -> usize {
    let core = core_slot();
    let mut guard = lock(core);
    let Some(core) = guard.as_mut() else {
        return 0;
    };
    match core.ensure_state_cache() {
        Ok(()) => core.state_cache.len(),
        Err(error) => {
            set_boot_error(error);
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_serialize(data: *mut c_void, size: usize) -> bool {
    if data.is_null() {
        return false;
    }
    let core = core_slot();
    let mut guard = lock(core);
    let Some(core) = guard.as_mut() else {
        return false;
    };
    if core.ensure_state_cache().is_err() || size < core.state_cache.len() {
        return false;
    }
    unsafe {
        std::ptr::copy_nonoverlapping(
            core.state_cache.as_ptr(),
            data.cast::<u8>(),
            core.state_cache.len(),
        );
    }
    true
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_unserialize(data: *const c_void, size: usize) -> bool {
    #[cfg(feature = "jit-runtime")]
    jgenesis_common::jit::invalidate();
    if data.is_null() || size == 0 {
        return false;
    }
    let bytes = unsafe { std::slice::from_raw_parts(data.cast::<u8>(), size) };
    let core = core_slot();
    let mut guard = lock(core);
    let Some(core) = guard.as_mut() else {
        return false;
    };
    core.load_state(bytes).is_ok()
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_set_controller_port_device(_port: u32, _device: u32) {}

#[unsafe(no_mangle)]
pub extern "C" fn retro_cheat_reset() {
    if let Some(core) = lock(core_slot()).as_mut() {
        core.cheats.clear();
        core.apply_cheats();
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn retro_cheat_set(index: u32, enabled: bool, code: *const c_char) {
    if code.is_null() {
        return;
    }
    let code = unsafe { CStr::from_ptr(code) }.to_string_lossy();
    if let Some(core) = lock(core_slot()).as_mut() {
        core.set_cheat(index, enabled, &code);
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_get_memory_data(_id: u32) -> *mut c_void {
    std::ptr::null_mut()
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_get_memory_size(_id: u32) -> usize {
    0
}

#[unsafe(no_mangle)]
pub extern "C" fn jgenesis_boot_error() -> *const c_char {
    let slot = boot_error_slot();
    let guard = lock(slot);
    guard.as_ref().map_or(std::ptr::null(), |value| value.as_ptr())
}

#[unsafe(no_mangle)]
pub extern "C" fn jgenesis_game_title() -> *const c_char {
    lock(core_slot()).as_ref().map_or(std::ptr::null(), |core| core.title.as_ptr())
}

/// QA only. Returns required capacity including NUL; copies only if capacity suffices.
/// Call and query on the emulation thread between retro_run calls.
#[cfg(feature = "jit-preview")]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn jgenesis_jit_snapshot_json(output: *mut c_char, capacity: usize) -> usize {
    let snapshot = jgenesis_common::jit::snapshot_json();
    let required = snapshot.len() + 1;
    if !output.is_null() && capacity >= required {
        unsafe {
            std::ptr::copy_nonoverlapping(snapshot.as_ptr(), output.cast::<u8>(), snapshot.len());
            output.add(snapshot.len()).write(0);
        }
    }
    required
}
#[cfg(feature = "jit-preview")]
#[unsafe(no_mangle)]
pub extern "C" fn jgenesis_jit_reset_counters() {
    jgenesis_common::jit::clear_counters();
}
