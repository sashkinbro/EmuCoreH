use crate::debug::Z80Debugger;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[cfg_attr(feature = "bincode", derive(bincode::Encode, bincode::Decode))]
pub enum InterruptLine {
    High,
    Low,
}

pub trait BusInterface {
    type DebugView<'a>: Z80Debugger
    where
        Self: 'a;

    /// Read a byte from the given memory address.
    fn read_memory(&mut self, address: u16) -> u8;

    /// Write the given byte to the given memory address.
    fn write_memory(&mut self, address: u16, value: u8);

    /// Read a byte from the given I/O port. Most hardware only uses the lower 8 bits of the address.
    fn read_io(&mut self, address: u16) -> u8;

    /// Write the given byte to the given I/O port. Most hardware only uses the lower 8 bits of the address.
    fn write_io(&mut self, address: u16, value: u8);

    /// Poll the NMI interrupt line.
    fn nmi(&self) -> InterruptLine;

    /// Poll the INT interrupt line.
    fn int(&self) -> InterruptLine;

    /// Poll the BUSREQ line; setting this halts the Z80
    fn busreq(&self) -> bool;

    /// Poll the RESET line; setting this resets and halts the Z80
    fn reset(&self) -> bool;

    /// Optional pure instruction-fetch view for an explicitly scheduled native window.
    ///
    /// Return None unless, for the entire `t_cycles` interval, instruction fetches
    /// from the returned contiguous slice have no observable effects, RESET,
    /// BUSREQ, INT and NMI remain constant, no device needs an intermediate CPU
    /// boundary, and no debugger execution hooks are active. The slice starts at
    /// `address`, represents the currently selected bank, and must stop at the
    /// address-space wrap or any region failing these conditions. Execution uses
    /// only register-only instructions, so no guest writes occur within a window.
    /// The default declines batching; existing bus implementations stay exact.
    #[cfg(feature = "native-z80")]
    fn native_code_window(&self, _address: u16, _t_cycles: u32) -> Option<&[u8]> {
        None
    }

    fn debug_view(&mut self) -> Option<Self::DebugView<'_>> {
        None
    }
}

#[cfg(test)]
#[derive(Debug, Clone)]
pub(crate) struct InMemoryBus {
    pub(crate) memory: [u8; 0x10000],
    pub(crate) io_ports: [u8; 0x100],
    pub(crate) nmi: InterruptLine,
    pub(crate) int: InterruptLine,
    pub(crate) reset: bool,
}

#[cfg(test)]
impl InMemoryBus {
    pub(crate) fn new() -> Self {
        use std::array;

        Self {
            memory: array::from_fn(|_| 0),
            io_ports: array::from_fn(|_| 0),
            nmi: InterruptLine::High,
            int: InterruptLine::High,
            reset: false,
        }
    }
}

#[cfg(test)]
impl BusInterface for InMemoryBus {
    type DebugView<'a>
        = crate::debug::DummyZ80Debugger
    where
        Self: 'a;

    fn read_memory(&mut self, address: u16) -> u8 {
        self.memory[address as usize]
    }

    fn write_memory(&mut self, address: u16, value: u8) {
        self.memory[address as usize] = value;
    }

    fn read_io(&mut self, address: u16) -> u8 {
        self.io_ports[(address & 0xFF) as usize]
    }

    fn write_io(&mut self, address: u16, value: u8) {
        self.io_ports[(address & 0xFF) as usize] = value;
    }

    fn nmi(&self) -> InterruptLine {
        self.nmi
    }

    fn int(&self) -> InterruptLine {
        self.int
    }

    fn busreq(&self) -> bool {
        false
    }

    fn reset(&self) -> bool {
        self.reset
    }
}
