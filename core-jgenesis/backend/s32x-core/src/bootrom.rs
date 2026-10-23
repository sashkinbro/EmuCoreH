use bincode::{Decode, Encode};
use jgenesis_proc_macros::{FakeDecode, FakeEncode};
use std::ops::{Deref, DerefMut};
use std::path::Path;

/// Per-instance user firmware. Firmware bytes are never serialized into save states.
#[derive(Debug, Clone, Default, FakeEncode, FakeDecode)]
pub struct BootRoms(Option<Box<Images>>);

#[derive(Debug, Clone)]
struct Images {
    m68k: [u8; 256],
    master: [u8; 2048],
    slave: [u8; 1024],
}

impl BootRoms {
    #[must_use]
    pub fn new(m68k: [u8; 256], master: [u8; 2048], slave: [u8; 1024]) -> Self {
        Self(Some(Box::new(Images { m68k, master, slave })))
    }

    /// Load the three canonical BIOS roles, requiring exact size and CRC32.
    /// # Errors
    /// Returns an actionable error for missing, unreadable, wrong-sized or wrong-role files.
    pub fn load(directory: &Path) -> Result<Self, String> {
        fn read<const N: usize>(
            directory: &Path,
            name: &str,
            role: &str,
            crc: u32,
        ) -> Result<[u8; N], String> {
            use std::io::Read;
            let path = directory.join(name);
            let fail =
                |reason: String| format!("SEGA_32X_BIOS_REQUIRED: {role} BIOS {name}: {reason}");
            let file = std::fs::File::open(&path).map_err(|error| fail(error.to_string()))?;
            let size = file.metadata().map_err(|error| fail(error.to_string()))?.len();
            if size != N as u64 {
                return Err(fail(format!("expected {N} bytes, found {size}")));
            }
            let mut bytes = Vec::with_capacity(N + 1);
            file.take((N + 1) as u64)
                .read_to_end(&mut bytes)
                .map_err(|error| fail(error.to_string()))?;
            if bytes.len() != N || crc32(&bytes) != crc {
                return Err(fail(format!("expected {N} bytes and CRC32 {crc:08X}")));
            }
            Ok(bytes.try_into().expect("validated BIOS length"))
        }
        let m68k = read(directory, "32X_G_BIOS.bin", "68000", 0x5c12eae8)?;
        let master = read(directory, "32X_M_BIOS.bin", "master SH-2", 0xdd9c46b8)?;
        let slave = read(directory, "32X_S_BIOS.bin", "slave SH-2", 0xbfda1fe5)?;
        Ok(Self::new(m68k, master, slave))
    }

    pub(crate) fn master(&self) -> &[u8; 2048] {
        &self.0.as_ref().expect("instance BIOS").master
    }
    pub(crate) fn slave(&self) -> &[u8; 1024] {
        &self.0.as_ref().expect("instance BIOS").slave
    }
    pub(crate) fn vectors(&self) -> M68kVectors {
        let mut bytes = self.0.as_ref().expect("instance BIOS").m68k;
        // HINT is writable and initialized to zero (hardware-verified by testpico).
        bytes[0x70..0x74].fill(0);
        M68kVectors(bytes)
    }
}

/// Keep the original 256-byte state layout, but only store writable HINT state.
/// Legacy states' immutable firmware bytes are discarded during decoding.
#[derive(Debug, Clone)]
pub struct M68kVectors([u8; 256]);
impl Deref for M68kVectors {
    type Target = [u8; 256];
    fn deref(&self) -> &Self::Target {
        &self.0
    }
}
impl DerefMut for M68kVectors {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.0
    }
}
impl Encode for M68kVectors {
    fn encode<E: bincode::enc::Encoder>(
        &self,
        encoder: &mut E,
    ) -> Result<(), bincode::error::EncodeError> {
        let mut state = [0; 256];
        state[0x70..0x74].copy_from_slice(&self.0[0x70..0x74]);
        state.encode(encoder)
    }
}
impl<C> Decode<C> for M68kVectors {
    fn decode<D: bincode::de::Decoder<Context = C>>(
        decoder: &mut D,
    ) -> Result<Self, bincode::error::DecodeError> {
        let mut state = <[u8; 256]>::decode(decoder)?;
        state[..0x70].fill(0);
        state[0x74..].fill(0);
        Ok(Self(state))
    }
}
bincode::impl_borrow_decode!(M68kVectors);

pub(crate) fn crc32(bytes: &[u8]) -> u32 {
    let mut crc = !0_u32;
    for &byte in bytes {
        crc ^= u32::from(byte);
        for _ in 0..8 {
            crc = (crc >> 1) ^ (0xedb88320 & 0_u32.wrapping_sub(crc & 1));
        }
    }
    !crc
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_directory(name: &str) -> std::path::PathBuf {
        let directory = std::env::temp_dir()
            .join(format!("jgenesis-32x-bootrom-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&directory);
        std::fs::create_dir_all(&directory).unwrap();
        directory
    }

    #[test]
    fn missing_firmware_is_actionable_and_names_the_role() {
        let directory = temp_directory("missing");
        let error = BootRoms::load(&directory).unwrap_err();
        assert!(error.starts_with("SEGA_32X_BIOS_REQUIRED: 68000 BIOS 32X_G_BIOS.bin:"));
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn wrong_size_and_wrong_role_firmware_is_rejected() {
        let directory = temp_directory("invalid");
        std::fs::write(directory.join("32X_G_BIOS.bin"), [0; 255]).unwrap();
        std::fs::write(directory.join("32X_M_BIOS.bin"), [0; 2048]).unwrap();
        std::fs::write(directory.join("32X_S_BIOS.bin"), [0; 1024]).unwrap();
        let error = BootRoms::load(&directory).unwrap_err();
        assert!(error.contains("expected 256 bytes, found 255"), "{error}");

        std::fs::write(directory.join("32X_G_BIOS.bin"), [0; 256]).unwrap();
        let error = BootRoms::load(&directory).unwrap_err();
        assert!(error.starts_with("SEGA_32X_BIOS_REQUIRED: 68000 BIOS 32X_G_BIOS.bin:"), "{error}");
        assert!(error.contains("CRC32 5C12EAE8"), "{error}");
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn vector_state_contains_only_the_writable_hint_slot() {
        let mut m68k = [0xaa; 256];
        m68k[0x70..0x74].copy_from_slice(&[1, 2, 3, 4]);
        let boot_roms = BootRoms::new(m68k, [0x11; 2048], [0x22; 1024]);
        let mut vectors = boot_roms.vectors();
        assert_eq!(boot_roms.master(), &[0x11; 2048]);
        assert_eq!(boot_roms.slave(), &[0x22; 1024]);
        assert_eq!(&vectors[..0x70], &[0xaa; 0x70]);
        assert_eq!(&vectors[0x70..0x74], &[0; 4]);
        assert_eq!(&vectors[0x74..], &[0xaa; 256 - 0x74]);

        vectors[0x70..0x74].copy_from_slice(&[9, 8, 7, 6]);
        let config = bincode::config::standard();
        let encoded = bincode::encode_to_vec(&vectors, config).unwrap();
        let (decoded, _) = bincode::decode_from_slice::<M68kVectors, _>(&encoded, config).unwrap();
        assert_eq!(encoded.len(), 256);
        assert_eq!(&decoded[0x70..0x74], &[9, 8, 7, 6]);
        assert!(decoded[..0x70].iter().all(|byte| *byte == 0));
        assert!(decoded[0x74..].iter().all(|byte| *byte == 0));
    }
}
