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
                (320, 224, 320, 480, 4.0 / 3.0)
            }
            Self::MasterSystem | Self::Sg1000 => (256, 192, 256, 240, 4.0 / 3.0),
            Self::GameGear => (160, 144, 256, 240, 10.0 / 9.0),
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

fn validate_cartridge(bytes: &[u8]) -> Result<(), String> {
    // This exact Nintendo DS Collection image replaces normal 68000 branch
    // instructions with emulator hooks. On Genesis hardware they enter the
    // game's LINE1010 exception handler, leaving its display disabled.
    // Never rewrite the image or infer incompatibility from its file name.
    const HOOKS: &[(usize, u16)] = &[
        (0x107e, 0xa6f6),
        (0x12ec, 0xa6f8),
        (0x131a, 0xa6f8),
        (0x29a8, 0xa6fa),
        (0x71b62, 0xa6f6),
        (0x72738, 0xa6f4),
        (0x72750, 0xa6f4),
    ];
    if bytes.len() == 524288
        && &bytes[0x180..0x18e] == b"GM 00004049-01"
        && HOOKS.iter().all(|(offset, opcode)| bytes[*offset..*offset + 2] == opcode.to_be_bytes())
        && crc::Crc::<u32>::new(&crc::CRC_32_ISO_HDLC).checksum(bytes) == 0x27e22795
    {
        return Err("UNSUPPORTED_DS_COLLECTION_ROM: This Sonic Classic Collection ROM contains Nintendo DS emulator hooks. Use an original Genesis/Mega Drive ROM release.".into());
    }
    // The protected Switch Aleste Collection dump has a normal Sega mapper, but
    // intentionally altered code that requires its collection emulator. This exact
    // CRC/size is documented by the original extractor and reverse engineers:
    // https://www.smspower.org/forums/18250-GGAleste3
    // Keep compatible dumps accepted; never identify this from a title or file name.
    if bytes.len() == 1048576
        && bytes[..16]
            == [
                0xf3, 0xed, 0x56, 0xc3, 0xd7, 0x01, 0x00, 0x00, 0x7b, 0xd3, 0xbf, 0x7a, 0xd3, 0xbf,
                0xc9, 0x00,
            ]
        && bytes[0x1d7..0x1e0] == [0x31, 0xef, 0xdf, 0x3a, 0x00, 0xc0, 0x32, 0x80, 0xdf]
        && bytes[0x7ff0..0x8000]
            == [
                0x54, 0x4d, 0x52, 0x20, 0x53, 0x45, 0x47, 0x41, 0x00, 0x00, 0x00, 0x00, 0x39, 0x23,
                0xf0, 0x50,
            ]
        && crc::Crc::<u32>::new(&crc::CRC_32_ISO_HDLC).checksum(bytes) == 0x5ae64731
    {
        return Err("UNSUPPORTED_ALESTE_COLLECTION_ROM: This protected GG Aleste 3 Collection image requires the original collection emulator. Select a compatible Game Gear ROM.".into());
    }
    Ok(())
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
            validate_cartridge(&candidate.0)?;
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
    validate_cartridge(&bytes)?;
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
    fn protected_aleste_collection_image_fails_with_actionable_error() {
        // Authored zero-filled fixture with only identification signatures. The final
        // four bytes match the protected image's CRC without bundling its game data.
        let mut bytes = vec![0; 1048576];
        bytes[..16].copy_from_slice(&[
            0xf3, 0xed, 0x56, 0xc3, 0xd7, 0x01, 0x00, 0x00, 0x7b, 0xd3, 0xbf, 0x7a, 0xd3, 0xbf,
            0xc9, 0x00,
        ]);
        bytes[0x1d7..0x1e0]
            .copy_from_slice(&[0x31, 0xef, 0xdf, 0x3a, 0x00, 0xc0, 0x32, 0x80, 0xdf]);
        bytes[0x7ff0..0x8000].copy_from_slice(&[
            0x54, 0x4d, 0x52, 0x20, 0x53, 0x45, 0x47, 0x41, 0x00, 0x00, 0x00, 0x00, 0x39, 0x23,
            0xf0, 0x50,
        ]);
        bytes[1048572..].copy_from_slice(&[0xc4, 0xda, 0x2c, 0xa4]);
        assert_eq!(crc::Crc::<u32>::new(&crc::CRC_32_ISO_HDLC).checksum(&bytes), 0x5ae64731);
        let path = std::env::temp_dir()
            .join(format!("jgenesis-protected-collection-{}.bin", std::process::id()));
        fs::write(&path, &bytes).unwrap();
        let error = read_cartridge(&path).err().expect("Protected Collection image must fail");
        assert!(error.starts_with("UNSUPPORTED_ALESTE_COLLECTION_ROM:"));
        assert!(error.contains("compatible Game Gear ROM"));
        let archive_path = path.with_extension("zip");
        let mut archive = zip::ZipWriter::new(File::create(&archive_path).unwrap());
        archive.start_file("game.gg", zip::write::SimpleFileOptions::default()).unwrap();
        std::io::Write::write_all(&mut archive, &bytes).unwrap();
        archive.finish().unwrap();
        assert!(
            read_cartridge(&archive_path)
                .err()
                .unwrap()
                .starts_with("UNSUPPORTED_ALESTE_COLLECTION_ROM:")
        );
        fs::remove_file(archive_path).unwrap();
        // Another dump with the same header/signatures must not be classified by name.
        bytes[1048575] ^= 1;
        fs::write(&path, &bytes).unwrap();
        assert_eq!(read_cartridge(&path).unwrap().0, bytes);
        fs::remove_file(path).unwrap();
    }

    #[test]
    fn collection_emulator_hooks_fail_with_actionable_error() {
        // Original synthetic bytes, not a bundled retail ROM. The final four bytes make
        // its CRC match the known image so this exercises the entire identification guard.
        let mut bytes = vec![0; 524288];
        bytes[0x100..0x104].copy_from_slice(b"SEGA");
        bytes[0x180..0x18e].copy_from_slice(b"GM 00004049-01");
        bytes[0x18e..0x190].copy_from_slice(&[0x39, 0x4d]);
        for (offset, opcode) in [
            (0x107e, 0xa6f6u16),
            (0x12ec, 0xa6f8),
            (0x131a, 0xa6f8),
            (0x29a8, 0xa6fa),
            (0x71b62, 0xa6f6),
            (0x72738, 0xa6f4),
            (0x72750, 0xa6f4),
        ] {
            bytes[offset..offset + 2].copy_from_slice(&opcode.to_be_bytes());
        }
        bytes[524284..].copy_from_slice(&[0x89, 0x6b, 0x28, 0x62]);
        let path =
            std::env::temp_dir().join(format!("jgenesis-unsupported-{}.md", std::process::id()));
        fs::write(&path, &bytes).unwrap();
        let error = read_cartridge(&path).err().expect("Collection CPU hooks must be rejected");
        assert!(error.starts_with("UNSUPPORTED_DS_COLLECTION_ROM:"));
        assert!(error.contains("original Genesis/Mega Drive"));
        let archive_path = path.with_extension("zip");
        let mut archive = zip::ZipWriter::new(File::create(&archive_path).unwrap());
        archive.start_file("game.md", zip::write::SimpleFileOptions::default()).unwrap();
        std::io::Write::write_all(&mut archive, &bytes).unwrap();
        archive.finish().unwrap();
        assert!(
            read_cartridge(&archive_path)
                .err()
                .unwrap()
                .starts_with("UNSUPPORTED_DS_COLLECTION_ROM:")
        );
        fs::remove_file(archive_path).unwrap();
        // A different full-ROM checksum or restored normal branch must never be rejected.
        bytes[524287] ^= 1;
        fs::write(&path, &bytes).unwrap();
        assert_eq!(read_cartridge(&path).unwrap().0, bytes);
        fs::remove_file(path).unwrap();
    }

    #[test]
    fn genesis_family_geometry_covers_interlaced_output() {
        for console in [Console::Genesis, Console::SegaCd, Console::Sega32X, Console::SegaCd32X] {
            let (base_width, base_height, max_width, max_height, _) = console.default_geometry();
            assert_eq!((base_width, base_height), (320, 224));
            // Horizontal borders stay disabled; NTSC/PAL VDP interlace doubles 224/240 lines.
            for (width, height) in [(256, 224), (320, 240), (256, 448), (320, 480)] {
                assert!(
                    width <= max_width && height <= max_height,
                    "{console:?} cannot present {width}x{height} within {max_width}x{max_height}"
                );
            }
        }
    }

    #[test]
    fn eight_bit_geometry_covers_exposed_resolution_and_border_options() {
        for console in [Console::MasterSystem, Console::GameGear, Console::Sg1000] {
            let (base_width, base_height, max_width, max_height, _) = console.default_geometry();
            if console == Console::GameGear {
                assert_eq!((base_width, base_height), (160, 144));
            }
            // Upstream viewport/buffer width is 256, including its left border. PAL is at most 240 lines.
            for (width, height) in [(160, 144), (256, 192), (256, 224), (256, 240)] {
                assert!(
                    width <= max_width && height <= max_height,
                    "{console:?} cannot present {width}x{height} within {max_width}x{max_height}"
                );
            }
        }
    }

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
