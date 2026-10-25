//! Experimental ARM64 instruction bodies and guarded, execution-learned blocks.

#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
fn emit(opcode: u16) -> Option<Vec<u32>> {
    let n = u32::from((opcode >> 8) & 15);
    let m = u32::from((opcode >> 4) & 15);
    let load = |r: u32, scratch: u32| 0xB9400000u32 | (r << 10) | scratch;
    let store = 0xB9000001 | (n << 10);
    let mut code = match opcode & 0xF000 {
        0xE000 => {
            let imm = opcode as u8 as i8 as i32 as u32;
            vec![0x52800001 | ((imm & 0xFFFF) << 5), 0x72A00001 | ((imm >> 16) << 5)]
        }
        0x7000 => {
            let imm = opcode as u8 as i8 as i32;
            let instruction = if imm < 0 { 0x51000021 } else { 0x11000021 };
            vec![load(n, 1), instruction | (imm.unsigned_abs() << 10)]
        }
        _ => match opcode & 0xF00F {
            0x6003 => vec![load(m, 1)],
            0x300C | 0x2009 | 0x200A | 0x200B => {
                let op = match opcode & 15 {
                    0xC => 0x0B020021, // add w1, w1, w2 (wraps, no flags)
                    0x9 => 0x0A020021, // and
                    0xA => 0x4A020021, // eor
                    0xB => 0x2A020021, // orr
                    _ => unreachable!(),
                };
                vec![load(n, 1), load(m, 2), op]
            }
            _ if opcode == 0x0009 => return Some(vec![0xD65F03C0]),
            _ => return None,
        },
    };
    code.extend([store, 0xD65F03C0]);
    Some(code)
}

#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
fn supported(opcode: u16) -> bool {
    matches!(opcode & 0xF000, 0xE000 | 0x7000)
        || matches!(opcode & 0xF00F, 0x6003 | 0x300C | 0x2009 | 0x200A | 0x200B)
        || opcode == 0x0009
}

/// Host execution context. Keep separate from `Sh2`: clone and savestate operations never
/// copy executable pointers. Unsupported hosts and allocation failures use the interpreter.
/// Blocks are learned from real executed fetches, never speculative guest-memory reads.
#[derive(Default)]
pub struct NativeExecutor {
    #[cfg(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    ))]
    cache: std::collections::HashMap<u16, jgenesis_common::native_code::PublishedCode>,
    #[cfg(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    ))]
    blocks: std::collections::HashMap<u32, jgenesis_common::native_code::PublishedCode>,
    #[cfg(all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    ))]
    trace: Vec<(u32, u16)>,
    native_blocks: u64,
    block_instructions: u64,
    budget_exits: u64,
    stop_exits: u64,
    interpreted_instructions: u64,
    guard_exits: u64,
    blocks_disabled: bool,
    disabled: bool,
    native_instructions: u64,
}

impl NativeExecutor {
    /// Count of instructions actually executed in generated ARM64 code, not cache hits or attempts.
    #[must_use]
    pub fn native_instructions(&self) -> u64 {
        self.native_instructions
    }

    /// Entries into generated multi-instruction code (including guarded exits).
    #[must_use]
    pub fn native_blocks(&self) -> u64 {
        self.native_blocks
    }

    /// Native ALU bodies executed inside multi-instruction code.
    #[must_use]
    pub fn block_instructions(&self) -> u64 {
        self.block_instructions
    }

    #[must_use]
    pub fn budget_exits(&self) -> u64 {
        self.budget_exits
    }

    #[must_use]
    pub fn stop_exits(&self) -> u64 {
        self.stop_exits
    }

    /// Instructions dispatched to the interpreter, including fetched guard mismatches.
    #[must_use]
    pub fn interpreted_instructions(&self) -> u64 {
        self.interpreted_instructions
    }

    /// Blocks whose next PC or fetched opcode no longer matched their learned trace.
    #[must_use]
    pub fn guard_exits(&self) -> u64 {
        self.guard_exits
    }

    pub(crate) fn finish_trace(&mut self) {
        #[cfg(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        ))]
        {
            let trace = std::mem::take(&mut self.trace);
            if self.disabled || trace.len() < 2 {
                return;
            }
            let Some(code) = block::emit(&trace) else {
                return;
            };
            let Some(body) = jgenesis_common::native_code::PublishedCode::publish(&code) else {
                self.disabled = true;
                return;
            };
            if self.blocks.len() == 256 {
                self.blocks.clear();
            }
            self.blocks.insert(trace[0].0, body);
        }
    }

    /// Keep single-instruction translation while disabling learned blocks, for A/B measurement.
    pub fn disable_blocks(&mut self) {
        self.blocks_disabled = true;
        #[cfg(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        ))]
        {
            self.blocks.clear();
            self.trace.clear();
        }
    }

    /// Disable translation (also useful when executable memory is forbidden by the host).
    pub fn disable(&mut self) {
        self.disabled = true;
    }

    pub(crate) fn execute(&mut self, gpr: &mut [u32; 16], opcode: u16) -> bool {
        if self.disabled {
            return false;
        }
        #[cfg(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        ))]
        {
            if !self.cache.contains_key(&opcode) {
                let Some(code) = emit(opcode) else {
                    return false;
                };
                let Some(body) = jgenesis_common::native_code::PublishedCode::publish(&code) else {
                    self.disabled = true;
                    return false;
                };
                // Bounded per-executor memory, including 16 KiB-page Android devices.
                if self.cache.len() == 256 {
                    self.cache.clear();
                }
                self.cache.insert(opcode, body);
            }
            // SAFETY: validated leaf emitter uses x0 for a valid borrowed [u32;16],
            // accesses indices 0..15, and preserves AAPCS64 callee-saved registers.
            unsafe {
                let run: unsafe extern "C" fn(*mut u32) =
                    std::mem::transmute(self.cache[&opcode].entry_pointer());
                run(gpr.as_mut_ptr());
            }
            self.native_instructions += 1;
            true
        }
        #[cfg(not(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        )))]
        {
            let _ = (gpr, opcode);
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn emits_register_abi_and_rejects_memory_and_flag_operations() {
        // x0 points directly at [u32; 16]; w1/w2 are caller-saved scratch registers.
        assert_eq!(emit(0x6123), Some(vec![0xB9400801, 0xB9000401, 0xD65F03C0]));
        assert_eq!(emit(0x0009), Some(vec![0xD65F03C0]));
        for opcode in [0x6122, 0x312E, 0xC900, 0xA001, 0xFFFF] {
            assert_eq!(emit(opcode), None, "{opcode:04X}");
        }
    }
}

#[cfg(test)]
mod differential;

#[cfg(any(
    test,
    all(
        target_arch = "aarch64",
        target_endian = "little",
        any(target_os = "android", target_os = "linux")
    )
))]
mod block;

impl<Bus: crate::bus::BusInterface> crate::ExecutionBody<Bus> for NativeExecutor {
    fn opcode(&mut self, gpr: &mut [u32; 16], pc: u32, opcode: u16) -> bool {
        #[cfg(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        ))]
        if !self.disabled && !self.blocks_disabled {
            if self.trace.last().is_some_and(|&(previous, _)| previous.wrapping_add(2) != pc)
                || !supported(opcode)
            {
                self.finish_trace();
            }
            if supported(opcode) {
                self.trace.push((pc, opcode));
                if self.trace.len() == 16 {
                    self.finish_trace();
                }
            }
        }
        let _ = pc;
        let executed = self.execute(gpr, opcode);
        self.interpreted_instructions += u64::from(!executed);
        executed
    }

    fn block(&mut self, cpu: &mut crate::Sh2, bus: &mut Bus, ticks: u64) -> (u64, bool) {
        #[cfg(all(
            target_arch = "aarch64",
            target_endian = "little",
            any(target_os = "android", target_os = "linux")
        ))]
        if !self.disabled && !self.blocks_disabled {
            return block::run(self, cpu, bus, ticks);
        }
        let _ = (cpu, bus, ticks);
        (0, false)
    }
}
