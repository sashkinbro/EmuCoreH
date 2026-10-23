use super::*;
use cdrom::cdtime::CdTime;

pub fn register(callback: retro_environment_t) {
    let mut disk = retro_disk_control_callback {
        set_eject_state,
        get_eject_state,
        get_image_index,
        set_image_index,
        get_num_images,
        replace_image_index,
        add_image_index,
    };
    unsafe {
        let mut version = 0u32;
        if callback(
            RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION,
            (&mut version as *mut u32).cast(),
        ) && version >= 1
        {
            let mut ext = retro_disk_control_ext_callback {
                base: disk,
                set_initial_image: None,
                get_image_path,
                get_image_label,
            };
            if callback(
                RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE,
                (&mut ext as *mut retro_disk_control_ext_callback).cast(),
            ) {
                return;
            }
        }
        callback(
            RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE,
            (&mut disk as *mut retro_disk_control_callback).cast(),
        );
    }
}

fn is_cd(core: &Core) -> bool {
    matches!(core.console, Console::SegaCd | Console::SegaCd32X)
}

unsafe extern "C" fn set_eject_state(ejected: bool) -> bool {
    let mut guard = lock(core_slot());
    let Some(core) = guard.as_mut().filter(|core| is_cd(core)) else {
        return false;
    };
    if core.disc_ejected == ejected {
        return true;
    }
    let Emulator::Genesis(emulator) = &mut core.emulator else {
        return false;
    };
    if ejected {
        emulator.remove_disc();
    } else {
        let path = core.disc_paths.get(core.disc_index as usize).and_then(Option::as_ref);
        let Some(path) = path else {
            core.disc_ejected = false;
            core.state_cache.clear();
            return true;
        };
        let Some(format) = format_from_path(path) else {
            return false;
        };
        let disc = match CdRom::open(path, format) {
            Ok(disc) => disc,
            Err(error) => {
                set_boot_error(error.to_string());
                return false;
            }
        };
        if let Err(error) = emulator.change_disc(disc) {
            set_boot_error(error.to_string());
            return false;
        }
        core.content_path = path.clone();
    }
    core.disc_ejected = ejected;
    core.state_cache.clear();
    true
}

unsafe extern "C" fn get_eject_state() -> bool {
    lock(core_slot()).as_ref().is_some_and(|core| is_cd(core) && core.disc_ejected)
}
unsafe extern "C" fn get_image_index() -> u32 {
    lock(core_slot()).as_ref().filter(|core| is_cd(core)).map_or(0, |core| core.disc_index)
}
unsafe extern "C" fn set_image_index(index: u32) -> bool {
    let mut guard = lock(core_slot());
    let Some(core) = guard.as_mut().filter(|core| is_cd(core) && core.disc_ejected) else {
        return false;
    };
    if index as usize > core.disc_paths.len() {
        return false;
    }
    core.disc_index = index;
    core.state_cache.clear();
    true
}
unsafe extern "C" fn get_num_images() -> u32 {
    lock(core_slot())
        .as_ref()
        .filter(|core| is_cd(core))
        .map_or(0, |core| core.disc_paths.len() as u32)
}
unsafe extern "C" fn replace_image_index(index: u32, game: *const retro_game_info) -> bool {
    let mut guard = lock(core_slot());
    let Some(core) = guard.as_mut().filter(|core| is_cd(core) && core.disc_ejected) else {
        return false;
    };
    if index as usize >= core.disc_paths.len() {
        return false;
    }
    if game.is_null() {
        core.disc_paths.remove(index as usize);
        if core.disc_index == index {
            core.disc_index = core.disc_paths.len() as u32;
        } else if core.disc_index > index {
            core.disc_index -= 1;
        }
        core.state_cache.clear();
        return true;
    }
    let pointer = unsafe { (*game).path };
    if pointer.is_null() {
        return false;
    }
    let path = PathBuf::from(unsafe { CStr::from_ptr(pointer) }.to_string_lossy().into_owned());
    if probe(&path) == 0 {
        set_boot_error("Replacement image is not a readable Sega CD disc".into());
        return false;
    }
    core.disc_paths[index as usize] = Some(path);
    core.state_cache.clear();
    true
}
unsafe extern "C" fn add_image_index() -> bool {
    let mut guard = lock(core_slot());
    let Some(core) = guard.as_mut().filter(|core| is_cd(core) && core.disc_ejected) else {
        return false;
    };
    if core.disc_paths.len() >= u32::MAX as usize {
        return false;
    }
    core.disc_paths.push(None);
    core.state_cache.clear();
    true
}

fn copy_string(value: &str, output: *mut c_char, length: usize) -> bool {
    if output.is_null() || length == 0 {
        return false;
    }
    unsafe {
        *output = 0;
    }
    if value.len() >= length {
        return false;
    }
    unsafe {
        std::ptr::copy_nonoverlapping(value.as_ptr(), output.cast::<u8>(), value.len());
        *output.add(value.len()) = 0;
    }
    true
}

unsafe extern "C" fn get_image_path(index: u32, output: *mut c_char, length: usize) -> bool {
    let guard = lock(core_slot());
    let Some(path) = guard
        .as_ref()
        .filter(|core| is_cd(core))
        .and_then(|core| core.disc_paths.get(index as usize))
        .and_then(Option::as_ref)
    else {
        return false;
    };
    copy_string(&path.to_string_lossy(), output, length)
}

unsafe extern "C" fn get_image_label(index: u32, output: *mut c_char, length: usize) -> bool {
    let guard = lock(core_slot());
    let Some(path) = guard
        .as_ref()
        .filter(|core| is_cd(core))
        .and_then(|core| core.disc_paths.get(index as usize))
        .and_then(Option::as_ref)
    else {
        return false;
    };
    copy_string(&path.file_stem().unwrap_or_default().to_string_lossy(), output, length)
}

pub fn probe(path: &Path) -> i32 {
    std::panic::catch_unwind(|| {
        let format = format_from_path(path).or_else(|| {
            use std::io::Read;
            let mut magic = [0; 8];
            std::fs::File::open(path).ok()?.read_exact(&mut magic).ok()?;
            (magic == *b"MComprHD").then_some(CdRomFileFormat::Chd)
        })?;
        classify_disc(CdRom::open(path, format).ok()?)
    })
    .ok()
    .flatten()
    .unwrap_or(0)
}

fn classify_disc(mut disc: CdRom) -> Option<i32> {
    if disc.cue().track(1).track_type != cdrom::cue::TrackType::Data { return None; }
    let mut sector = [0; cdrom::BYTES_PER_SECTOR as usize];
    disc.read_sector(1, CdTime::SECTOR_0_START, &mut sector).ok()?;
    let header = &sector[16..32];
    if !header.starts_with(b"SEGADISCSYSTEM") && !header.starts_with(b"SEGABOOT") { return None; }
    Some(if segacd_core::is_cd_32x_disc(&mut disc) { 2 } else { 1 })
}

/// Borrow the provider grant by duplicating the descriptor, never reopening its path.
#[unsafe(no_mangle)]
pub extern "C" fn jgenesis_probe_disc_fd(fd: i32) -> i32 {
    #[cfg(unix)]
    {
        use std::os::fd::BorrowedFd;
        use std::io::{Seek, SeekFrom};
        if fd < 0 { return 0; }
        return std::panic::catch_unwind(|| {
            let owned = unsafe { BorrowedFd::borrow_raw(fd) }.try_clone_to_owned().ok()?;
            let mut file = std::fs::File::from(owned);
            file.seek(SeekFrom::Start(0)).ok()?;
            classify_disc(CdRom::open_chd_file(file).ok()?)
        }).ok().flatten().unwrap_or(0);
    }
    #[cfg(not(unix))]
    { let _ = fd; 0 }
}

/// Read-only content classification; does not initialize or modify the active core.
#[unsafe(no_mangle)]
pub extern "C" fn jgenesis_probe_disc(path: *const c_char) -> i32 {
    if path.is_null() {
        return 0;
    }
    let value = unsafe { CStr::from_ptr(path) }.to_string_lossy();
    probe(Path::new(value.as_ref()))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn probe_requires_decoded_sega_signature() {
        let directory = std::env::temp_dir().join(format!("jgenesis-disc-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        let cue = directory.join("disc.cue");
        std::fs::write(
            &cue,
            "FILE \"disc.bin\" BINARY\n TRACK 01 MODE1/2048\n INDEX 01 00:00:00\n",
        )
        .unwrap();
        let mut bytes = vec![0; 2048 * 16];
        std::fs::write(directory.join("disc.bin"), &bytes).unwrap();
        assert_eq!(probe(&cue), 0);
        bytes[..14].copy_from_slice(b"SEGADISCSYSTEM");
        std::fs::write(directory.join("disc.bin"), &bytes).unwrap();
        assert_eq!(probe(&cue), 1);
        bytes[0x100..0x108].copy_from_slice(b"SEGA 32X");
        std::fs::write(directory.join("disc.bin"), &bytes).unwrap();
        assert_eq!(probe(&cue), 2);
        assert_eq!(probe(&directory.join("missing.chd")), 0);
        std::fs::remove_dir_all(directory).unwrap();
    }
}
