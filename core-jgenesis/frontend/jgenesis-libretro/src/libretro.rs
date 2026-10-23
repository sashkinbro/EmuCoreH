// Minimal libretro ABI definitions shared with the EmuCoreH frontend.
//
// The signatures and constants mirror core/core/deps/libretro-common/include/libretro.h
// from the vendored Flycast tree. Only the subset used by this core is declared.

#![allow(non_camel_case_types)]
#![allow(dead_code)]

use std::os::raw::{c_char, c_void};

pub const RETRO_API_VERSION: u32 = 1;

pub const RETRO_DEVICE_JOYPAD: u32 = 1;

pub const RETRO_DEVICE_ID_JOYPAD_B: u32 = 0;
pub const RETRO_DEVICE_ID_JOYPAD_Y: u32 = 1;
pub const RETRO_DEVICE_ID_JOYPAD_SELECT: u32 = 2;
pub const RETRO_DEVICE_ID_JOYPAD_START: u32 = 3;
pub const RETRO_DEVICE_ID_JOYPAD_UP: u32 = 4;
pub const RETRO_DEVICE_ID_JOYPAD_DOWN: u32 = 5;
pub const RETRO_DEVICE_ID_JOYPAD_LEFT: u32 = 6;
pub const RETRO_DEVICE_ID_JOYPAD_RIGHT: u32 = 7;
pub const RETRO_DEVICE_ID_JOYPAD_A: u32 = 8;
pub const RETRO_DEVICE_ID_JOYPAD_X: u32 = 9;
pub const RETRO_DEVICE_ID_JOYPAD_L: u32 = 10;
pub const RETRO_DEVICE_ID_JOYPAD_R: u32 = 11;
pub const RETRO_DEVICE_ID_JOYPAD_L2: u32 = 12;
pub const RETRO_DEVICE_ID_JOYPAD_R2: u32 = 13;
pub const RETRO_DEVICE_ID_JOYPAD_L3: u32 = 14;
pub const RETRO_DEVICE_ID_JOYPAD_R3: u32 = 15;

pub const RETRO_MEMORY_SAVE_RAM: u32 = 0;
pub const RETRO_MEMORY_RTC: u32 = 1;
pub const RETRO_MEMORY_SYSTEM_RAM: u32 = 2;

pub const RETRO_ENVIRONMENT_GET_CAN_DUPE: u32 = 1;
pub const RETRO_ENVIRONMENT_SET_MESSAGE: u32 = 6;
pub const RETRO_ENVIRONMENT_SHUTDOWN: u32 = 7;
pub const RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: u32 = 9;
pub const RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: u32 = 10;
pub const RETRO_ENVIRONMENT_SET_HW_RENDER: u32 = 14;
pub const RETRO_ENVIRONMENT_GET_VARIABLE: u32 = 15;
pub const RETRO_ENVIRONMENT_SET_VARIABLES: u32 = 16;
pub const RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: u32 = 17;
pub const RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME: u32 = 18;
pub const RETRO_ENVIRONMENT_GET_LOG_INTERFACE: u32 = 27;
pub const RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY: u32 = 30;
pub const RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: u32 = 31;
pub const RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: u32 = 32;
pub const RETRO_ENVIRONMENT_SET_GEOMETRY: u32 = 37;
pub const RETRO_ENVIRONMENT_GET_LANGUAGE: u32 = 39;
pub const RETRO_ENVIRONMENT_GET_FASTFORWARDING: u32 = 49;
pub const RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: u32 = 51;
pub const RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION: u32 = 57;
pub const RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE: u32 = 13;
pub const RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER: u32 = 56;
pub const RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE: u32 = 58;
pub const RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION: u32 = 59;

pub const RETRO_PIXEL_FORMAT_XRGB8888: u32 = 1;

pub const RETRO_LOG_DEBUG: u32 = 0;
pub const RETRO_LOG_INFO: u32 = 1;
pub const RETRO_LOG_WARN: u32 = 2;
pub const RETRO_LOG_ERROR: u32 = 3;

pub type retro_environment_t = unsafe extern "C" fn(cmd: u32, data: *mut c_void) -> bool;
pub type retro_video_refresh_t =
    unsafe extern "C" fn(data: *const c_void, width: u32, height: u32, pitch: usize);
pub type retro_audio_sample_t = unsafe extern "C" fn(left: i16, right: i16);
pub type retro_audio_sample_batch_t =
    unsafe extern "C" fn(data: *const i16, frames: usize) -> usize;
pub type retro_input_poll_t = unsafe extern "C" fn();
pub type retro_input_state_t =
    unsafe extern "C" fn(port: u32, device: u32, index: u32, id: u32) -> i16;
pub type retro_log_printf_t = unsafe extern "C" fn(level: u32, fmt: *const c_char, ...);

#[repr(C)]
pub struct retro_game_info {
    pub path: *const c_char,
    pub data: *const c_void,
    pub size: usize,
    pub meta: *const c_char,
}

#[repr(C)]
pub struct retro_system_info {
    pub library_name: *const c_char,
    pub library_version: *const c_char,
    pub valid_extensions: *const c_char,
    pub need_fullpath: bool,
    pub block_extract: bool,
}

#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct retro_game_geometry {
    pub base_width: u32,
    pub base_height: u32,
    pub max_width: u32,
    pub max_height: u32,
    pub aspect_ratio: f32,
}

#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct retro_system_timing {
    pub fps: f64,
    pub sample_rate: f64,
}

#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct retro_system_av_info {
    pub geometry: retro_game_geometry,
    pub timing: retro_system_timing,
}

#[repr(C)]
pub struct retro_variable {
    pub key: *const c_char,
    pub value: *const c_char,
}

#[repr(C)]
pub struct retro_log_callback {
    pub log: Option<retro_log_printf_t>,
}

#[repr(C)]
pub struct retro_message {
    pub msg: *const c_char,
    pub frames: u32,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct retro_disk_control_callback {
    pub set_eject_state: unsafe extern "C" fn(bool) -> bool,
    pub get_eject_state: unsafe extern "C" fn() -> bool,
    pub get_image_index: unsafe extern "C" fn() -> u32,
    pub set_image_index: unsafe extern "C" fn(u32) -> bool,
    pub get_num_images: unsafe extern "C" fn() -> u32,
    pub replace_image_index: unsafe extern "C" fn(u32, *const retro_game_info) -> bool,
    pub add_image_index: unsafe extern "C" fn() -> bool,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct retro_disk_control_ext_callback {
    pub base: retro_disk_control_callback,
    pub set_initial_image: Option<unsafe extern "C" fn(u32, *const c_char) -> bool>,
    pub get_image_path: unsafe extern "C" fn(u32, *mut c_char, usize) -> bool,
    pub get_image_label: unsafe extern "C" fn(u32, *mut c_char, usize) -> bool,
}
