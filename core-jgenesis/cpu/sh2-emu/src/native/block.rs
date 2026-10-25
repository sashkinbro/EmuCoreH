//! Guarded blocks learned only from completed real instruction fetches. Generated code
//! contains the ALU bodies directly; it never calls the per-opcode native executor.
//! Every body is preceded by the normal SH-2 fetch/debug/PC/context preparation and
//! followed by cycle accounting and the bus stop check, including on an opcode mismatch.

pub(super) fn emit(trace: &[(u32, u16)]) -> Option<Vec<u32>> {
    if trace.len() < 2 || trace.len() > 16 {
        return None;
    }
    // stp x19,x30,[sp,#-16]!; mov x19,x0. x19 holds the runtime across callbacks.
    let mut code = vec![0xA9BF7BF3, 0xAA0003F3];
    let mut exits = Vec::new();
    for &(pc, opcode) in trace {
        let mut body = super::emit(opcode)?;
        body.pop(); // inline the leaf body, removing RET
        code.extend([
            0xAA1303E0, // mov x0,x19 (runtime)
            0x52800001 | ((pc & 0xFFFF) << 5),
            0x72A00001 | ((pc >> 16) << 5),
            0x52800002 | (u32::from(opcode) << 5),
            0xF9400270, // ldr x16,[x19] (prepare callback)
            0xD63F0200, // blr x16 -> x0 = register array, or null on exit
        ]);
        exits.push(code.len());
        code.push(0xB4000000); // cbz x0,exit
        code.extend(body);
    }
    // The next prepare callback completes the preceding instruction before fetching.
    // Only the final instruction needs a separate completion callback: N+1 calls
    // per N bodies rather than two calls per instruction.
    code.extend([
        0xAA1303E0, // mov x0,x19
        0xF9400670, // ldr x16,[x19,#8] (finish callback)
        0xD63F0200,
    ]);
    let exit = code.len();
    for branch in exits {
        code[branch] |= ((exit - branch) as u32) << 5;
    }
    code.extend([0xA8C17BF3, 0xD65F03C0]); // ldp x19,x30,[sp],#16; ret
    Some(code)
}

#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
mod execution {
    use super::super::NativeExecutor;
    use crate::{Sh2, bus::BusInterface};

    // First two fields form the generated code's ABI. No Sh2 layout is embedded in code.
    // The live, stack-owned runtime cannot escape run(). No executor mutation, code-cache
    // eviction, or recursive entry is possible during a generated call.
    #[repr(C)]
    struct Runtime<'a, Bus: BusInterface> {
        prepare: unsafe extern "C" fn(*mut Runtime<'a, Bus>, u32, u16) -> *mut u32,
        finish: unsafe extern "C" fn(*mut Runtime<'a, Bus>) -> u32,
        cpu: &'a mut Sh2,
        bus: &'a mut Bus,
        budget: u64,
        executed: u64,
        native: u64,
        guards: u64,
        stopped: bool,
        pending_native: bool,
    }

    impl<Bus: BusInterface> Runtime<'_, Bus> {
        fn complete(&mut self) -> u32 {
            self.bus.increment_cycle_counter(1);
            self.executed += 1;
            self.stopped = self.bus.should_stop_execution();
            u32::from(self.stopped || self.executed == self.budget)
        }
    }

    unsafe extern "C" fn prepare<Bus: BusInterface>(
        runtime: *mut Runtime<'_, Bus>,
        pc: u32,
        expected: u16,
    ) -> *mut u32 {
        // SAFETY: generated code passes its live run()-owned runtime exclusively.
        let r = unsafe { &mut *runtime };
        if r.pending_native {
            r.pending_native = false;
            r.native += 1;
            if r.complete() != 0 {
                return std::ptr::null_mut();
            }
        }
        if r.cpu.registers.pc != pc {
            r.guards += 1;
            return std::ptr::null_mut();
        }
        let opcode = r.cpu.prepare_instruction(r.bus);
        if opcode != expected {
            r.guards += 1;
            // Already fetched once, with cache/LRU and bus side effects. Execute that
            // exact opcode here: returning to the outer interpreter to fetch again is wrong.
            Bus::opcode_table().decode(opcode)(r.cpu, opcode, r.bus);
            r.complete();
            return std::ptr::null_mut();
        }
        r.pending_native = true;
        r.cpu.registers.gpr.as_mut_ptr()
    }

    unsafe extern "C" fn finish<Bus: BusInterface>(runtime: *mut Runtime<'_, Bus>) -> u32 {
        let r = unsafe { &mut *runtime };
        debug_assert!(r.pending_native);
        r.pending_native = false;
        r.native += 1;
        r.complete()
    }

    pub(in super::super) fn run<Bus: BusInterface>(
        executor: &mut NativeExecutor,
        cpu: &mut Sh2,
        bus: &mut Bus,
        ticks: u64,
    ) -> (u64, bool) {
        let entry_pc = cpu.pc();
        let Some(code) = executor.blocks.get(&entry_pc) else {
            return (0, false);
        };
        let mut runtime = Runtime {
            prepare: prepare::<Bus>,
            finish: finish::<Bus>,
            cpu,
            bus,
            budget: ticks,
            executed: 0,
            native: 0,
            guards: 0,
            stopped: false,
            pending_native: false,
        };
        // SAFETY: emitter preserves x19/LR and a 16-byte-aligned stack; accesses only
        // callback-provided registers 0..15. Its callback slots are repr(C), pointer-sized,
        // and initialized for this bus monomorphization on every entry. The code owner
        // remains borrowed for the whole call. C callbacks must not unwind (as with other
        // extern C boundaries, a bus panic aborts rather than crossing generated frames).
        unsafe {
            let call: unsafe extern "C" fn(*mut Runtime<'_, Bus>) =
                std::mem::transmute(code.entry_pointer());
            call(&mut runtime);
        }
        executor.native_instructions += runtime.native;
        executor.native_blocks += 1;
        executor.block_instructions += runtime.native;
        executor.budget_exits += u64::from(runtime.executed == ticks);
        executor.stop_exits += u64::from(runtime.stopped);
        executor.interpreted_instructions += runtime.executed - runtime.native;
        executor.guard_exits += runtime.guards;
        if runtime.guards != 0 {
            // The entry is no longer a useful prediction. The generated call has
            // returned, so dropping its owner is safe; later real fetches can relearn it.
            executor.blocks.remove(&entry_pc);
        }
        (runtime.executed, runtime.stopped)
    }
}

#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
pub(super) use execution::run;

#[cfg(test)]
mod tests {
    #[test]
    fn block_inlines_bodies_and_branches_to_balanced_epilogue() {
        let code = super::emit(&[(0x20000100, 0xE101), (0x20000102, 0x7101)]).unwrap();
        assert_eq!(&code[..2], &[0xA9BF7BF3, 0xAA0003F3]);
        assert_eq!(&code[code.len() - 2..], &[0xA8C17BF3, 0xD65F03C0]);
        assert_eq!(code.iter().filter(|&&word| word == 0xD65F03C0).count(), 1);
        let mut branches = 0;
        for (i, &word) in code.iter().enumerate() {
            if word & 0xFF000000 == 0xB4000000 || word & 0xFF000000 == 0x35000000 {
                assert_eq!(i + ((word >> 5) & 0x7FFFF) as usize, code.len() - 2);
                branches += 1;
            }
        }
        assert_eq!(branches, 2);
        assert!(super::emit(&[(0, 0xE101), (2, 0x6122)]).is_none());
        assert!(super::emit(&[(0, 0xE101)]).is_none());
    }
}
