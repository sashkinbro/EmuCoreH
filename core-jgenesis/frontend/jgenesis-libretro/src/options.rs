use super::*;
use jgenesis_common::frontend::TimingMode;

const OPTIONS: &[(&CStr, &CStr)] = &[
    #[cfg(feature = "jit-runtime")]
    (c"jgenesis_jit_backend", c"QA experimental CPU backend; interpreter|native"),
    (c"jgenesis_region", c"Region (restart); auto|americas|europe|japan"),
    (c"jgenesis_timing", c"Timing (restart); auto|ntsc|pal"),
    (c"jgenesis_remove_sprite_limit", c"Remove sprite limit; disabled|enabled"),
    (c"jgenesis_genesis_lpf", c"Genesis low-pass filter; enabled|disabled"),
    (c"jgenesis_sms_fm", c"Master System FM sound; enabled|disabled"),
    (c"jgenesis_sms_crop_vertical_border", c"Master System crop vertical border; enabled|disabled"),
    (c"jgenesis_gg_sms_resolution", c"Game Gear full SMS resolution; disabled|enabled"),
];

pub fn register(callback: retro_environment_t) {
    let mut variables: Vec<_> = OPTIONS
        .iter()
        .map(|(key, value)| retro_variable { key: key.as_ptr(), value: value.as_ptr() })
        .collect();
    variables.push(retro_variable { key: std::ptr::null(), value: std::ptr::null() });
    unsafe {
        callback(RETRO_ENVIRONMENT_SET_VARIABLES, variables.as_mut_ptr().cast());
    }
}

fn value(key: &CStr) -> Option<String> {
    let callback = lock(environment()).frontend?;
    let mut variable = retro_variable { key: key.as_ptr(), value: std::ptr::null() };
    if !unsafe {
        callback(RETRO_ENVIRONMENT_GET_VARIABLE, (&mut variable as *mut retro_variable).cast())
    } || variable.value.is_null()
    {
        return None;
    }
    Some(unsafe { CStr::from_ptr(variable.value) }.to_string_lossy().into_owned())
}

fn enabled(key: &CStr, default: bool) -> bool {
    value(key).map_or(default, |value| value == "enabled")
}

pub fn save_id() -> Option<String> {
    value(c"jgenesis_save_id").filter(|value| valid_save_id(value))
}

fn valid_save_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value.bytes().all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn save_id_cannot_escape_directory() {
        assert!(valid_save_id("megadrive_8fa-34"));
        for value in ["", "../save", "/save", "C:\\save", ".", "game.sav"] {
            assert!(!valid_save_id(value));
        }
        assert!(!valid_save_id(&"a".repeat(129)));
    }
}

pub fn changed() -> bool {
    let Some(callback) = lock(environment()).frontend else {
        return false;
    };
    let mut changed = false;
    unsafe {
        callback(RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE, (&mut changed as *mut bool).cast());
    }
    changed
}

pub fn genesis() -> GenesisEmulatorConfig {
    let mut config = GenesisEmulatorConfig::default();
    config.forced_region = match value(c"jgenesis_region").as_deref() {
        Some("americas") => Some(GenesisRegion::Americas),
        Some("europe") => Some(GenesisRegion::Europe),
        Some("japan") => Some(GenesisRegion::Japan),
        _ => None,
    };
    config.forced_timing_mode = match value(c"jgenesis_timing").as_deref() {
        Some("ntsc") => Some(TimingMode::Ntsc),
        Some("pal") => Some(TimingMode::Pal),
        _ => None,
    };
    config.remove_sprite_limits = enabled(c"jgenesis_remove_sprite_limit", false);
    config.genesis_lpf_enabled = enabled(c"jgenesis_genesis_lpf", true);
    config
}

pub fn smsgg(console: Console) -> SmsGgEmulatorConfig {
    let mut config = super::smsgg_config();
    config.sms_timing_mode = if value(c"jgenesis_timing").as_deref() == Some("pal") {
        TimingMode::Pal
    } else {
        TimingMode::Ntsc
    };
    config.forced_region = match value(c"jgenesis_region").as_deref() {
        Some("japan") => Some(smsgg_config::SmsGgRegion::Domestic),
        Some("americas" | "europe") => Some(smsgg_config::SmsGgRegion::International),
        _ => None,
    };
    config.remove_sprite_limit = enabled(c"jgenesis_remove_sprite_limit", false);
    config.fm_sound_unit_enabled =
        console == Console::MasterSystem && enabled(c"jgenesis_sms_fm", true);
    config.sms_crop_vertical_border = enabled(c"jgenesis_sms_crop_vertical_border", true);
    config.gg_use_sms_resolution = enabled(c"jgenesis_gg_sms_resolution", false);
    config
}

#[cfg(feature = "jit-runtime")]
pub fn jit_backend_native() -> bool {
    value(c"jgenesis_jit_backend").as_deref() == Some("native")
}
