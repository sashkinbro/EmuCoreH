use std::ffi::CStr;
use std::process::Command;
use std::sync::atomic::{AtomicUsize, Ordering};

#[allow(dead_code)]
#[path = "../src/diagnostics.rs"]
mod diagnostics;

#[test]
fn release_logging_has_no_argument_side_effects_or_custom_reports() {
    if cfg!(debug_assertions) {
        return;
    }
    let output = Command::new(std::env::current_exe().unwrap())
        .args(["--exact", "release_logging_probe", "--nocapture"])
        .env("JGENESIS_RELEASE_LOG_PROBE", "1")
        .output()
        .unwrap();
    assert!(output.status.success(), "{}", String::from_utf8_lossy(&output.stderr));
    let stderr = String::from_utf8(output.stderr).unwrap();
    assert!(stderr.is_empty(), "Release emitted custom diagnostic output: {stderr}");
}

#[test]
fn release_logging_probe() {
    if std::env::var_os("JGENESIS_RELEASE_LOG_PROBE").is_none() {
        return;
    }
    log::set_max_level(log::LevelFilter::Trace);
    let evaluations = AtomicUsize::new(0);
    log::error!("{}", evaluations.fetch_add(1, Ordering::Relaxed));
    log::warn!("{}", evaluations.fetch_add(1, Ordering::Relaxed));
    log::info!("{}", evaluations.fetch_add(1, Ordering::Relaxed));
    log::debug!("{}", evaluations.fetch_add(1, Ordering::Relaxed));
    log::trace!("{}", evaluations.fetch_add(1, Ordering::Relaxed));
    assert_eq!(evaluations.load(Ordering::Relaxed), 0, "Release evaluated diagnostic arguments");
    assert_eq!(log::STATIC_MAX_LEVEL, log::LevelFilter::Off);

    static PANIC_HOOK_CALLS: AtomicUsize = AtomicUsize::new(0);
    let original_hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(|_| { PANIC_HOOK_CALLS.fetch_add(1, Ordering::Relaxed); }));
    diagnostics::install_panic_hook();
    diagnostics::reset_error_report();
    for _ in 0..2 {
        diagnostics::report_unrecoverable_error("duplicate fatal operation probe");
    }

    jgenesis_libretro::retro_init();
    for _ in 0..2 {
        assert!(!jgenesis_libretro::retro_load_game(std::ptr::null()));
        let error = unsafe { CStr::from_ptr(jgenesis_libretro::jgenesis_boot_error()) };
        assert!(error.to_string_lossy().contains("null game info"));
    }
    for _ in 0..2 {
        assert!(std::panic::catch_unwind(|| panic!("release fatal panic probe")).is_err());
    }
    assert_eq!(PANIC_HOOK_CALLS.load(Ordering::Relaxed), 2,
        "Release must leave the application's panic hook untouched");
    std::panic::set_hook(original_hook);
    jgenesis_libretro::retro_deinit();
}
