//! Host-side regression oracle and benchmark for the jgenesis libretro core.
//!
//! Drives the same libretro entry points the Android frontend uses (linked directly as an
//! rlib), runs N unpaced frames with an optional scripted pad input and, every K frames,
//! records SHA-256 of the full save state (retro_serialize) and of the presented XRGB8888
//! frame. Audio output is folded into a running SHA-256 as an extra check. Mirrors
//! app/src/androidTest/.../CoreOracleInstrumentedTest.kt (input masks use the app pad bits).
//!
//! Usage:
//!   jg-host-oracle --rom <path> --system <dir> [--frames 1800] [--every 300]
//!                  [--input "from-to:mask,..."] [--label name] [--json out.json]
//!                  [--save <dir>] [--option key=value]...

use jgenesis_libretro::*;
use sha2::{Digest, Sha256};
use std::ffi::{CStr, CString, c_char, c_void};
use std::fmt::Write as _;
use std::path::PathBuf;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::time::Instant;

const RETRO_ENVIRONMENT_GET_CAN_DUPE: u32 = 1;
const RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: u32 = 9;
const RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: u32 = 10;
const RETRO_ENVIRONMENT_GET_VARIABLE: u32 = 15;
const RETRO_ENVIRONMENT_SET_VARIABLES: u32 = 16;
const RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: u32 = 17;
const RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY: u32 = 30;
const RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: u32 = 31;
const RETRO_DEVICE_JOYPAD: u32 = 1;

#[repr(C)]
struct RetroVariable {
    key: *const c_char,
    value: *const c_char,
}

#[repr(C)]
struct RetroGameInfo {
    path: *const c_char,
    data: *const c_void,
    size: usize,
    meta: *const c_char,
}

struct Host {
    system: CString,
    save: CString,
    options: Vec<(CString, CString)>,
}

static HOST: Mutex<Option<Host>> = Mutex::new(None);
static PAD_MASK: AtomicU32 = AtomicU32::new(0);
static CAPTURE: AtomicBool = AtomicBool::new(false);
static FRAME: Mutex<Option<(u32, u32, Vec<u8>)>> = Mutex::new(None);
static AUDIO: Mutex<Option<Sha256>> = Mutex::new(None);

unsafe extern "C" fn environment(cmd: u32, data: *mut c_void) -> bool {
    let guard = HOST.lock().unwrap();
    let host = guard.as_ref().unwrap();
    unsafe {
        match cmd {
            RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY | RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY => {
                *data.cast::<*const c_char>() = host.system.as_ptr();
                true
            }
            RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY => {
                *data.cast::<*const c_char>() = host.save.as_ptr();
                true
            }
            RETRO_ENVIRONMENT_GET_VARIABLE => {
                let variable = &mut *data.cast::<RetroVariable>();
                let key = CStr::from_ptr(variable.key);
                match host.options.iter().find(|(k, _)| k.as_c_str() == key) {
                    Some((_, value)) => {
                        variable.value = value.as_ptr();
                        true
                    }
                    None => false,
                }
            }
            RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE => {
                *data.cast::<bool>() = false;
                true
            }
            RETRO_ENVIRONMENT_SET_PIXEL_FORMAT | RETRO_ENVIRONMENT_SET_VARIABLES => true,
            RETRO_ENVIRONMENT_GET_CAN_DUPE => {
                *data.cast::<bool>() = true;
                true
            }
            _ => false,
        }
    }
}

unsafe extern "C" fn video(data: *const c_void, width: u32, height: u32, pitch: usize) {
    if data.is_null() || !CAPTURE.load(Ordering::Relaxed) {
        return;
    }
    let row = width as usize * 4;
    let mut pixels = Vec::with_capacity(row * height as usize);
    for y in 0..height as usize {
        let line = unsafe { std::slice::from_raw_parts(data.cast::<u8>().add(y * pitch), row) };
        pixels.extend_from_slice(line);
    }
    *FRAME.lock().unwrap() = Some((width, height, pixels));
}

unsafe extern "C" fn audio_batch(data: *const i16, frames: usize) -> usize {
    if !data.is_null() && frames > 0 {
        let bytes = unsafe { std::slice::from_raw_parts(data.cast::<u8>(), frames * 4) };
        if let Some(hasher) = AUDIO.lock().unwrap().as_mut() {
            hasher.update(bytes);
        }
    }
    frames
}

unsafe extern "C" fn audio_sample(_left: i16, _right: i16) {}
unsafe extern "C" fn input_poll() {}

unsafe extern "C" fn input_state(port: u32, device: u32, _index: u32, id: u32) -> i16 {
    if port != 0 || device != RETRO_DEVICE_JOYPAD {
        return 0;
    }
    // App pad bit layout (see native_bridge.cpp RetroInputState), pressed = 1.
    let mask = PAD_MASK.load(Ordering::Relaxed);
    let pressed = |bit: u32| mask & (1 << bit) != 0;
    let active = match id {
        4 => pressed(4),        // UP
        5 => pressed(6),        // DOWN
        6 => pressed(7),        // LEFT
        7 => pressed(5),        // RIGHT
        9 => pressed(12),       // X  (Triangle)
        8 => pressed(13),       // A  (Circle)
        0 => pressed(14),       // B  (Cross)
        1 => pressed(15),       // Y  (Square)
        10 => pressed(10),      // L
        11 => pressed(11),      // R
        12 => pressed(8),       // L2
        13 => pressed(9),       // R2
        14 => pressed(1),       // L3
        15 => pressed(2),       // R3
        3 => pressed(3),        // START
        2 => pressed(0),        // SELECT
        _ => false,
    };
    i16::from(active)
}

/// Minimal sampling profiler (Windows x64 only): a helper thread suspends the emulation thread
/// at ~1 kHz and records RIP as an RVA. Symbolize with tools/oracle/host_jg_profile.sh.
#[cfg(all(windows, target_arch = "x86_64"))]
mod sampler {
    use std::collections::HashMap;
    use std::ffi::c_void;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, Ordering};

    #[repr(C, align(16))]
    struct Context([u8; 1232]);

    unsafe extern "system" {
        fn GetCurrentThread() -> *mut c_void;
        fn GetCurrentProcess() -> *mut c_void;
        fn DuplicateHandle(
            sp: *mut c_void,
            sh: *mut c_void,
            tp: *mut c_void,
            th: *mut *mut c_void,
            access: u32,
            inherit: i32,
            options: u32,
        ) -> i32;
        fn SuspendThread(thread: *mut c_void) -> u32;
        fn ResumeThread(thread: *mut c_void) -> u32;
        fn GetThreadContext(thread: *mut c_void, context: *mut Context) -> i32;
        fn GetModuleHandleW(name: *const u16) -> *mut c_void;
    }

    pub struct Sampler {
        stop: Arc<AtomicBool>,
        handle: std::thread::JoinHandle<HashMap<u64, u64>>,
    }

    struct SendPtr(*mut c_void);
    unsafe impl Send for SendPtr {}

    pub fn start() -> Sampler {
        let mut target = std::ptr::null_mut();
        unsafe {
            DuplicateHandle(
                GetCurrentProcess(),
                GetCurrentThread(),
                GetCurrentProcess(),
                &mut target,
                0,
                0,
                2, // DUPLICATE_SAME_ACCESS
            );
        }
        let target = SendPtr(target);
        let base = unsafe { GetModuleHandleW(std::ptr::null()) } as u64;
        let stop = Arc::new(AtomicBool::new(false));
        let flag = Arc::clone(&stop);
        let handle = std::thread::spawn(move || {
            let target = target;
            let mut hist = HashMap::new();
            let mut ctx = Box::new(Context([0; 1232]));
            while !flag.load(Ordering::Relaxed) {
                // Sleep(1) has ~15 ms granularity on Windows; spin for a ~4 kHz sample rate.
                let until = std::time::Instant::now() + std::time::Duration::from_micros(250);
                while std::time::Instant::now() < until {
                    std::hint::spin_loop();
                }
                unsafe {
                    if SuspendThread(target.0) == u32::MAX {
                        continue;
                    }
                    ctx.0[0x30..0x34].copy_from_slice(&0x0010_0001u32.to_le_bytes()); // CONTEXT_CONTROL
                    if GetThreadContext(target.0, &mut *ctx) != 0 {
                        let rip = u64::from_le_bytes(ctx.0[0xF8..0x100].try_into().unwrap());
                        *hist.entry(rip.wrapping_sub(base)).or_insert(0) += 1;
                    }
                    ResumeThread(target.0);
                }
            }
            hist
        });
        Sampler { stop, handle }
    }

    impl Sampler {
        pub fn finish(self, path: &std::path::Path) {
            self.stop.store(true, Ordering::Relaxed);
            let hist = self.handle.join().unwrap();
            let mut rows: Vec<_> = hist.into_iter().collect();
            rows.sort_by(|a, b| b.1.cmp(&a.1));
            let text: String = rows.iter().map(|(rva, n)| format!("{rva:x} {n}\n")).collect();
            std::fs::write(path, text).unwrap();
        }
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().fold(String::with_capacity(bytes.len() * 2), |mut s, b| {
        let _ = write!(s, "{b:02x}");
        s
    })
}

fn sha256(bytes: &[u8]) -> String {
    hex(&Sha256::digest(bytes))
}

fn parse_input(spec: &str) -> Vec<(u32, u32, u32)> {
    spec.split(',')
        .filter(|part| !part.trim().is_empty())
        .map(|part| {
            let (range, mask) = part.trim().split_once(':').expect("input entry from-to:mask");
            let (from, to) = range.split_once('-').unwrap_or((range, range));
            (from.parse().unwrap(), to.parse().unwrap(), mask.parse().unwrap())
        })
        .collect()
}

fn json_escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"")
}

fn main() {
    let mut rom = None;
    let mut system = None;
    let mut save = None;
    let mut frames: u32 = 1800;
    let mut every: u32 = 300;
    let mut input = String::new();
    let mut label = String::from("run");
    let mut json = None;
    let mut profile: Option<PathBuf> = None;
    let mut options = Vec::new();
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        let mut next = || args.next().unwrap_or_else(|| panic!("missing value for {arg}"));
        match arg.as_str() {
            "--rom" => rom = Some(PathBuf::from(next())),
            "--system" => system = Some(PathBuf::from(next())),
            "--save" => save = Some(PathBuf::from(next())),
            "--frames" => frames = next().parse().expect("frames"),
            "--every" => every = next().parse().expect("every"),
            "--input" => input = next(),
            "--label" => label = next(),
            "--json" => json = Some(PathBuf::from(next())),
            "--profile" => profile = Some(PathBuf::from(next())),
            "--option" => {
                let value = next();
                let (k, v) = value.split_once('=').expect("--option key=value");
                options.push((CString::new(k).unwrap(), CString::new(v).unwrap()));
            }
            other => panic!("unknown argument {other}"),
        }
    }
    let rom = rom.expect("--rom required");
    let system = system.expect("--system required");
    assert!(frames >= 1 && every >= 1);
    // A fresh save directory per run keeps battery RAM from leaking between runs.
    let save = save.unwrap_or_else(|| {
        std::env::temp_dir().join(format!("jg-host-oracle-{}-{label}", std::process::id()))
    });
    let _ = std::fs::remove_dir_all(&save);
    std::fs::create_dir_all(&save).unwrap();
    let script = parse_input(&input);

    *HOST.lock().unwrap() = Some(Host {
        system: CString::new(system.to_string_lossy().as_bytes()).unwrap(),
        save: CString::new(save.to_string_lossy().as_bytes()).unwrap(),
        options,
    });
    *AUDIO.lock().unwrap() = Some(Sha256::new());

    retro_set_environment(Some(environment));
    retro_set_video_refresh(Some(video));
    retro_set_audio_sample(Some(audio_sample));
    retro_set_audio_sample_batch(Some(audio_batch));
    retro_set_input_poll(Some(input_poll));
    retro_set_input_state(Some(input_state));
    retro_init();

    // JG_PIN_CPU=<n>: pin the emulation thread to one logical CPU at high priority (less noise).
    #[cfg(windows)]
    if let Some(cpu) = std::env::var("JG_PIN_CPU").ok().and_then(|v| v.parse::<u32>().ok()) {
        unsafe extern "system" {
            fn GetCurrentThread() -> *mut c_void;
            fn SetThreadAffinityMask(thread: *mut c_void, mask: usize) -> usize;
            fn SetThreadPriority(thread: *mut c_void, priority: i32) -> i32;
        }
        unsafe {
            SetThreadAffinityMask(GetCurrentThread(), 1usize << cpu);
            SetThreadPriority(GetCurrentThread(), 2); // THREAD_PRIORITY_HIGHEST
        }
    }

    let path = CString::new(rom.to_string_lossy().as_bytes()).unwrap();
    let info = RetroGameInfo {
        path: path.as_ptr(),
        data: std::ptr::null(),
        size: 0,
        meta: std::ptr::null(),
    };
    let load_start = Instant::now();
    // SAFETY: layout-compatible with retro_game_info.
    if !retro_load_game((&info as *const RetroGameInfo).cast()) {
        let error = unsafe { CStr::from_ptr(jgenesis_boot_error()) }.to_string_lossy().into_owned();
        eprintln!("ORACLE_ERROR label={label} boot failed: {error}");
        std::process::exit(2);
    }
    let load_ms = load_start.elapsed().as_secs_f64() * 1000.0;

    #[cfg(all(windows, target_arch = "x86_64"))]
    let sampler = profile.as_ref().map(|_| sampler::start());
    let mut checkpoints = Vec::new();
    let mut total_run = 0.0f64;
    let mut segment = 0.0f64;
    let mut all = Sha256::new();
    for frame in 1..=frames {
        let mask = script
            .iter()
            .find(|(from, to, _)| (*from..=*to).contains(&frame))
            .map_or(0, |entry| entry.2);
        PAD_MASK.store(mask, Ordering::Relaxed);
        let checkpoint = frame % every == 0 || frame == frames;
        CAPTURE.store(checkpoint, Ordering::Relaxed);
        let start = Instant::now();
        retro_run();
        let elapsed = start.elapsed().as_secs_f64() * 1000.0;
        total_run += elapsed;
        segment += elapsed;
        if checkpoint {
            let size = retro_serialize_size();
            let mut state = vec![0u8; size];
            assert!(size > 0 && retro_serialize(state.as_mut_ptr().cast(), size), "serialize failed");
            let state_hash = sha256(&state);
            let frame_hash = FRAME
                .lock()
                .unwrap()
                .take()
                .map(|(w, h, pixels)| (w, h, sha256(&pixels)));
            let audio_hash = hex(&AUDIO.lock().unwrap().as_ref().unwrap().clone().finalize());
            let segment_frames = if frame % every == 0 { every } else { frame % every };
            let ms = segment / f64::from(segment_frames);
            let (w, h, fh) = frame_hash.unwrap_or((0, 0, "-".into()));
            println!(
                "ORACLE label={label} frame={frame} state={} frame_hash={} {w}x{h} audio={} state_bytes={size} ms_per_frame={ms:.4}",
                &state_hash[..16],
                &fh[..fh.len().min(16)],
                &audio_hash[..16]
            );
            all.update(format!("{frame}:{state_hash}:{fh}:{audio_hash}\n").as_bytes());
            checkpoints.push((frame, state_hash, fh, w, h, audio_hash, ms));
            segment = 0.0;
        }
    }
    #[cfg(all(windows, target_arch = "x86_64"))]
    if let (Some(sampler), Some(path)) = (sampler, profile.as_ref()) {
        sampler.finish(path);
    }
    let digest = hex(&all.finalize());
    let ms_per_frame = total_run / f64::from(frames);
    println!(
        "ORACLE_SUMMARY label={label} frames={frames} digest={digest} run_ms={total_run:.1} ms_per_frame={ms_per_frame:.4} load_ms={load_ms:.1}"
    );

    if let Some(json) = json {
        let mut out = String::new();
        let _ = write!(
            out,
            "{{\"label\":\"{}\",\"rom\":\"{}\",\"frames\":{frames},\"every\":{every},\"input\":\"{}\",\"digest\":\"{digest}\",\"run_ms\":{total_run:.3},\"ms_per_frame\":{ms_per_frame:.5},\"load_ms\":{load_ms:.3},\"checkpoints\":[",
            json_escape(&label),
            json_escape(&rom.file_name().unwrap_or_default().to_string_lossy()),
            json_escape(&input)
        );
        for (i, (frame, state, fh, w, h, audio, ms)) in checkpoints.iter().enumerate() {
            let _ = write!(
                out,
                "{}{{\"frame\":{frame},\"stateHash\":\"{state}\",\"frameHash\":\"{fh}\",\"width\":{w},\"height\":{h},\"audioHash\":\"{audio}\",\"msPerFrame\":{ms:.5}}}",
                if i == 0 { "" } else { "," }
            );
        }
        out.push_str("]}\n");
        std::fs::write(json, out).unwrap();
    }

    retro_unload_game();
    retro_deinit();
    let _ = std::fs::remove_dir_all(&save);
}
