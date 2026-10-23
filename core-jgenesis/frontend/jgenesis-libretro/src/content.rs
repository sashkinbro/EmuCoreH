use genesis_config::GenesisRegion;
use s32x_core::SECURITY_PROGRAM_CARTRIDGE_ADDR;
use std::ffi::OsStr;
use std::fs::{self, File};
use std::io::Read;
use std::path::{Path, PathBuf};
use zip::ZipArchive;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Console {
    Genesis,
    SegaCd,
    Sega32X,
    SegaCd32X,
    MasterSystem,
    GameGear,
    Sg1000,
}

impl Console {
    pub fn label(self) -> &'static str {
        match self {
            Self::Genesis => "Genesis",
            Self::SegaCd => "Sega CD",
            Self::Sega32X => "32X",
            Self::SegaCd32X => "Sega CD 32X",
            Self::MasterSystem => "Master System",
            Self::GameGear => "Game Gear",
            Self::Sg1000 => "SG-1000",
        }
    }

    pub fn default_geometry(self) -> (u32, u32, u32, u32, f32) {
        match self {
            Self::Genesis | Self::SegaCd | Self::Sega32X | Self::SegaCd32X => {
                (320, 224, 320, 240, 4.0 / 3.0)
            }
            Self::MasterSystem | Self::Sg1000 => (256, 192, 256, 240, 4.0 / 3.0),
            Self::GameGear => (160, 144, 160, 144, 10.0 / 9.0),
        }
    }
}

fn extension_of(path: &Path) -> String {
    path.extension()
        .and_then(OsStr::to_str)
        .map(|value| value.to_ascii_lowercase())
        .unwrap_or_default()
}

fn supported_archive_extension(extension: &str) -> bool {
    matches!(extension, "md" | "gen" | "bin" | "smd" | "32x" | "sms" | "gg" | "sg")
}

fn has_s32x_security_program(bytes: &[u8]) -> bool {
    let start = SECURITY_PROGRAM_CARTRIDGE_ADDR;
    let program = s32x_core::security_program();
    let end = start.saturating_add(program.len());
    bytes.len() >= end && &bytes[start..end] == program
}

pub fn is_disc_path(path: &Path) -> bool {
    matches!(extension_of(path).as_str(), "cue" | "chd")
}

pub fn detect_cartridge_console(extension: &str, bytes: &[u8]) -> Console {
    match extension {
        "sms" => return Console::MasterSystem,
        "gg" => return Console::GameGear,
        "sg" => return Console::Sg1000,
        "32x" => return Console::Sega32X,
        _ => {}
    }

    for address in [0x1ff0, 0x3ff0, 0x7ff0] {
        if bytes.get(address..address + 8) == Some(b"TMR SEGA") {
            let region = bytes.get(address + 15).copied().unwrap_or(0) >> 4;
            return if (5..=7).contains(&region) {
                Console::GameGear
            } else {
                Console::MasterSystem
            };
        }
    }

    if has_s32x_security_program(bytes) { Console::Sega32X } else { Console::Genesis }
}

/// Reads a cartridge ROM from a plain file or the first supported entry of a ZIP.
///
/// Returns the bytes and the extension used for console detection.
pub fn read_cartridge(path: &Path) -> Result<(Vec<u8>, String), String> {
    const MAX_ROM_BYTES: u64 = 64 * 1024 * 1024;
    let extension = extension_of(path);

    if extension == "zip" {
        let file = File::open(path)
            .map_err(|error| format!("Unable to open archive {}: {error}", path.display()))?;
        let mut archive = ZipArchive::new(file)
            .map_err(|error| format!("Unable to read ZIP {}: {error}", path.display()))?;

        let mut candidate = None;
        for index in 0..archive.len() {
            let mut entry = archive.by_index(index).map_err(|error| {
                format!("Unable to read ZIP entry in {}: {error}", path.display())
            })?;
            if entry.is_dir() || entry.name().starts_with("__MACOSX/") {
                continue;
            }
            let name = entry.name().to_ascii_lowercase();
            let entry_extension = Path::new(&name)
                .extension()
                .and_then(OsStr::to_str)
                .unwrap_or_default()
                .to_ascii_lowercase();
            if matches!(entry_extension.as_str(), "cue" | "chd") {
                return Err(
                    "Disc images in ZIP must be extracted with all tracks before launch".into()
                );
            }
            if !supported_archive_extension(&entry_extension) {
                continue;
            }
            if candidate.is_some() {
                return Err(
                    "ZIP contains multiple Sega ROMs; extract the intended game first".into()
                );
            }
            if entry.size() == 0 || entry.size() > MAX_ROM_BYTES {
                return Err("ZIP ROM is empty or exceeds the 64 MiB cartridge limit".into());
            }
            let mut bytes = Vec::with_capacity(entry.size() as usize);
            entry
                .read_to_end(&mut bytes)
                .map_err(|error| format!("Unable to extract {}: {error}", entry.name()))?;
            candidate = Some((bytes, entry_extension));
        }

        if let Some(candidate) = candidate {
            return Ok(candidate);
        }

        return Err(format!("No supported Sega ROM found inside {}", path.display()));
    }

    let size = fs::metadata(path).map_err(|error| format!("Unable to inspect ROM: {error}"))?.len();
    if size == 0 || size > MAX_ROM_BYTES {
        return Err("ROM is empty or exceeds the 64 MiB cartridge limit".into());
    }
    let bytes = fs::read(path)
        .map_err(|error| format!("Unable to read ROM {}: {error}", path.display()))?;
    Ok((bytes, extension))
}

pub fn read_bios_file(path: &Path) -> Result<Vec<u8>, String> {
    fs::read(path).map_err(|error| format!("Unable to read BIOS {}: {error}", path.display()))
}

pub fn sega_cd_bios_path(system_dir: &Path, region: GenesisRegion) -> Option<PathBuf> {
    let specific = match region {
        GenesisRegion::Americas => ["bios_cd_u.bin", "bios_cd_us.bin", "scd_us.bin", "bios_us.bin"],
        GenesisRegion::Europe => ["bios_cd_e.bin", "bios_cd_eu.bin", "scd_eu.bin", "bios_eu.bin"],
        GenesisRegion::Japan => ["bios_cd_j.bin", "bios_cd_jp.bin", "scd_jp.bin", "bios_jp.bin"],
    };

    let entries = fs::read_dir(system_dir).ok()?;
    let mut candidates = Vec::new();

    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        let name =
            path.file_name().and_then(OsStr::to_str).unwrap_or_default().to_ascii_lowercase();
        let size = entry.metadata().ok().map(|metadata| metadata.len());
        if size != Some(128 * 1024) {
            continue;
        }
        if specific.contains(&name.as_str()) {
            candidates.push(path.clone());
        }
    }

    candidates.sort();
    if let Some(path) = candidates.into_iter().next() {
        return Some(path);
    }

    None
}

pub fn base_name_for_saves(path: &Path) -> String {
    let name = path.file_stem().and_then(OsStr::to_str).unwrap_or("game").to_ascii_lowercase();
    let name = strip_disc_marker(&name);
    let sanitized: String = name
        .chars()
        .map(|character| {
            if character.is_ascii_alphanumeric() || matches!(character, '-' | '_' | ' ') {
                character
            } else {
                '_'
            }
        })
        .collect();
    let sanitized = sanitized.trim().trim_matches('.').to_string();
    if sanitized.is_empty() { "game".to_string() } else { sanitized }
}

fn strip_disc_marker(name: &str) -> String {
    let mut value = name.to_string();
    for marker in
        [" (disc ", " [disc ", " (disk ", " [disk ", "-disc-", "_disc_", "[disc-", "(disc-"]
    {
        if let Some(index) = value.find(marker) {
            value.truncate(index);
            break;
        }
    }
    value
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strips_disc_markers() {
        assert_eq!(base_name_for_saves(Path::new("Sonic CD (Disc 2).cue")), "sonic cd");
        assert_eq!(base_name_for_saves(Path::new("Game-[Disc-1].chd")), "game-");
    }

    #[test]
    fn detects_cartridge_console_from_extension() {
        assert_eq!(detect_cartridge_console("sms", &[]), Console::MasterSystem);
        assert_eq!(detect_cartridge_console("gg", &[]), Console::GameGear);
        assert_eq!(detect_cartridge_console("sg", &[]), Console::Sg1000);
        assert_eq!(detect_cartridge_console("32x", &[]), Console::Sega32X);
        assert_eq!(detect_cartridge_console("md", &[]), Console::Genesis);
    }

    #[test]
    fn bin_cartridges_use_smsgg_header() {
        let mut bytes = vec![0; 0x8000];
        bytes[0x7ff0..0x7ff8].copy_from_slice(b"TMR SEGA");
        bytes[0x7fff] = 0x40;
        assert_eq!(detect_cartridge_console("bin", &bytes), Console::MasterSystem);
        bytes[0x7fff] = 0x60;
        assert_eq!(detect_cartridge_console("bin", &bytes), Console::GameGear);
    }

    #[test]
    fn rejects_ambiguous_zip_and_reads_single_rom() {
        use std::io::Write;
        let path =
            std::env::temp_dir().join(format!("jgenesis-archive-{}.zip", std::process::id()));
        let mut archive = zip::ZipWriter::new(File::create(&path).unwrap());
        let options = zip::write::SimpleFileOptions::default();
        archive.start_file("game.gg", options).unwrap();
        archive.write_all(&[1, 2, 3]).unwrap();
        archive.finish().unwrap();
        assert_eq!(read_cartridge(&path).unwrap(), (vec![1, 2, 3], "gg".into()));
        let mut archive =
            zip::ZipWriter::new_append(File::options().read(true).write(true).open(&path).unwrap())
                .unwrap();
        archive.start_file("other.sms", options).unwrap();
        archive.write_all(&[4, 5, 6]).unwrap();
        archive.finish().unwrap();
        assert!(read_cartridge(&path).unwrap_err().contains("multiple"));
        fs::remove_file(path).unwrap();
    }

    #[test]
    fn bios_lookup_requires_requested_region_and_correct_size() {
        let directory = std::env::temp_dir().join(format!("jgenesis-bios-{}", std::process::id()));
        fs::create_dir_all(&directory).unwrap();
        fs::write(directory.join("bios_CD_J.bin"), vec![0; 128 * 1024]).unwrap();
        assert!(sega_cd_bios_path(&directory, GenesisRegion::Americas).is_none());
        assert!(sega_cd_bios_path(&directory, GenesisRegion::Japan).is_some());
        fs::write(directory.join("bios_CD_U.bin"), vec![0; 1024]).unwrap();
        assert!(sega_cd_bios_path(&directory, GenesisRegion::Americas).is_none());
        fs::remove_dir_all(directory).unwrap();
    }
}
