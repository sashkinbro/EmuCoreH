pub mod api;
pub mod audio;
mod bootrom;
mod bus;
mod pwm;
mod registers;
mod vdp;

pub use bootrom::BootRoms;

type GenesisVdp = genesis_components::vdp::Vdp;

pub const SH2_CLOCK_MULTIPLIER: u64 = genesis_config::NATIVE_SH2_MULTIPLIER;

// The security program is located at $36C-$76B in the master SH-2 boot ROM. The 32X will refuse to
// boot any cartridge where $400-$7FF in cartridge ROM isn't an exact match for this security program.
//
// This can be used to auto-detect whether ROM files with generic extensions (e.g. .bin) are 32X ROMs
pub const SECURITY_PROGRAM_CARTRIDGE_ADDR: usize = 0x400;
pub const SECURITY_PROGRAM_LEN: usize = 0x400;

#[inline]
#[must_use]
pub fn has_security_program(cartridge: &[u8]) -> bool {
    cartridge
        .get(
            SECURITY_PROGRAM_CARTRIDGE_ADDR..SECURITY_PROGRAM_CARTRIDGE_ADDR + SECURITY_PROGRAM_LEN,
        )
        .is_some_and(|program| bootrom::crc32(program) == 0xf6547f0a)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WhichCpu {
    Master = 0,
    Slave = 1,
}

impl WhichCpu {
    #[inline]
    #[must_use]
    pub fn other(self) -> Self {
        match self {
            Self::Master => Self::Slave,
            Self::Slave => Self::Master,
        }
    }
}
