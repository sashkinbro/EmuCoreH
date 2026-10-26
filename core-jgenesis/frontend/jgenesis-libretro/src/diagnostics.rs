// Shipping builds have no custom log/error counters or panic-hook installation.
// Boot errors remain available through jgenesis_boot_error; the host/OS retains
// its normal crash reporting policy.
#[inline]
pub fn reset_error_report() {}

#[inline]
pub fn install_panic_hook() {}

#[inline]
pub fn report_unrecoverable_error(message: &str) {
    #[cfg(debug_assertions)]
    write_fatal(message);
    #[cfg(not(debug_assertions))]
    let _ = message;
}

#[cfg(debug_assertions)]
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
