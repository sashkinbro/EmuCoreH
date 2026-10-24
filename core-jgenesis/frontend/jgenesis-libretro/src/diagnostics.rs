use std::sync::atomic::{AtomicBool, Ordering};

static ERROR_REPORTED: AtomicBool = AtomicBool::new(false);

pub fn reset_error_report() {
    ERROR_REPORTED.store(false, Ordering::Relaxed);
}

pub fn report_unrecoverable_error(message: &str) {
    if cfg!(debug_assertions) || !ERROR_REPORTED.swap(true, Ordering::Relaxed) {
        write_fatal(message);
    }
}

pub fn install_panic_hook() {
    #[cfg(not(debug_assertions))]
    {
        static INSTALL: std::sync::Once = std::sync::Once::new();
        static PANIC_REPORTED: AtomicBool = AtomicBool::new(false);
        INSTALL.call_once(|| {
            std::panic::set_hook(Box::new(|info| {
                if !PANIC_REPORTED.swap(true, Ordering::Relaxed) {
                    write_fatal(&format!("Fatal Rust panic: {info}"));
                }
            }));
        });
    }
}

// Fatal reports deliberately bypass the log facade's release_max_level_off.
fn write_fatal(message: &str) {
    #[cfg(target_os = "android")]
    {
        use std::ffi::{CString, c_char};
        #[link(name = "log")]
        unsafe extern "C" {
            fn __android_log_write(priority: i32, tag: *const c_char, text: *const c_char) -> i32;
        }
        if let Ok(text) = CString::new(message.replace('\0', "\\0")) {
            unsafe { __android_log_write(7, c"jgenesis_libretro".as_ptr(), text.as_ptr()) };
        }
    }
    #[cfg(not(target_os = "android"))]
    eprintln!("[jgenesis_libretro] {message}");
}
