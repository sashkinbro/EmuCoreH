mod core;
pub mod debug;
mod disassemble;
pub mod traits;

#[cfg(feature = "native-z80")]
pub use core::native;
pub use core::{Flags, InterruptMode, Registers, Z80};
pub use disassemble::{DisassembledInstruction, IndirectRegister, MemoryAccess, disassemble_into};
pub use traits::BusInterface;
