//! Guarded blocks learned only from completed real instruction fetches. Generated code
//! contains all legal instruction bodies directly; it never calls the per-opcode native executor.
//! Every body is preceded by the normal SH-2 fetch/debug/PC/context preparation and
//! followed by cycle accounting and the bus stop check, including on an opcode mismatch.

pub(super) fn inline_body(opcode: u16) -> Option<Vec<u32>> {
    let mut body = super::emit(opcode)?;
    body.pop();
    let (head, tail) = if body.first() == Some(&0xA9BC53F3) {
        (4, 4)
    } else if body.first() == Some(&0xA9BE53F3) {
        (2, 2)
    } else if body.first() == Some(&0xA9BF7BF3) {
        (1, 1)
    } else {
        (0, 0)
    };
    body.truncate(body.len() - tail);
    body.drain(..head);
    Some(body)
}

pub(super) fn emit(trace: &[(u32, u16)]) -> Option<Vec<u32>> {
    if trace.len() < 2 || trace.len() > 16 {
        return None;
    }
    // Preserve all callee-saved scratch used by any instruction body once per block.
    // x28 holds Runtime; body callbacks preserve it under AAPCS64.
    let mut code = vec![0xA9BB53F3, 0xA9015BF5, 0xA90263F7, 0xA90373F9, 0xA9047BFD, 0xAA0003FC];
    let mut exits = Vec::new();
    for &(pc, opcode) in trace {
        let body = inline_body(opcode)?;
        code.extend([
            0xAA1C03E0, // mov x0,x28 (runtime)
            0x52800001 | ((pc & 0xFFFF) << 5),
            0x72A00001 | ((pc >> 16) << 5),
            0x52800002 | (u32::from(opcode) << 5),
            0xF9400390, // ldr x16,[x28] (prepare callback)
            0xD63F0200, // blr x16 -> x0 = Context, or null on exit
        ]);
        exits.push(code.len());
        code.push(0xB4000000); // cbz x0,exit
        code.extend(body);
    }
    // The next prepare callback completes the preceding instruction before fetching.
    // Only the final instruction needs a separate completion callback: N+1 calls
    // per N bodies rather than two calls per instruction.
    code.extend([
        0xAA1C03E0, // mov x0,x28
        0xF9400790, // ldr x16,[x28,#8] (finish callback)
        0xD63F0200,
    ]);
    let exit = code.len();
    for branch in exits {
        code[branch] |= ((exit - branch) as u32) << 5;
    }
    code.extend([0xA9447BFD, 0xA94373F9, 0xA94263F7, 0xA9415BF5, 0xA8C553F3, 0xD65F03C0]);
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
        prepare:
            unsafe extern "C" fn(*mut Runtime<'a, Bus>, u32, u16) -> *mut super::super::Context,
        finish: unsafe extern "C" fn(*mut Runtime<'a, Bus>) -> u32,
        context: super::super::Context,
        memory_runtime: super::super::memory::Runtime<Bus>,
        cpu: &'a mut Sh2,
        bus: &'a mut Bus,
        budget: u64,
        executed: u64,
        native: u64,
        guards: u64,
        stopped: bool,
        pending_native: bool,
        pending_opcode: u16,
        fetched_mismatch: Option<u16>,
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
    ) -> *mut super::super::Context {
        // SAFETY: generated code passes its live run()-owned runtime exclusively.
        let r = unsafe { &mut *runtime };
        if r.pending_native {
            r.pending_native = false;
            #[cfg(any(test, feature = "jit-preview"))]
            {
                r.native += 1;
            }
            #[cfg(feature = "jit-preview")]
            jgenesis_common::jit::record_opcode(r.pending_opcode, true);
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
            // Preserve the already fetched opcode; translation/cache mutation must
            // happen only after the generated frame has returned.
            r.fetched_mismatch = Some(opcode);
            return std::ptr::null_mut();
        }
        r.pending_native = true;
        r.pending_opcode = opcode;
        &mut r.context
    }

    unsafe extern "C" fn finish<Bus: BusInterface>(runtime: *mut Runtime<'_, Bus>) -> u32 {
        let r = unsafe { &mut *runtime };
        debug_assert!(r.pending_native);
        r.pending_native = false;
        #[cfg(any(test, feature = "jit-preview"))]
        {
            r.native += 1;
        }
        #[cfg(feature = "jit-preview")]
        jgenesis_common::jit::record_opcode(r.pending_opcode, true);
        r.complete()
    }

    pub(in super::super) fn run<Bus: BusInterface>(
        executor: &mut NativeExecutor,
        cpu: &mut Sh2,
        bus: &mut Bus,
        ticks: u64,
    ) -> (u64, bool) {
        let entry_pc = cpu.pc();
        executor.cache_clock = executor.cache_clock.wrapping_add(1);
        if let Some((age, _, _)) = executor.block_metadata.get_mut(&entry_pc) {
            *age = executor.cache_clock;
        }
        let Some(code) = executor.blocks.get(&entry_pc) else {
            return (0, false);
        };
        let mut runtime = Runtime {
            prepare: prepare::<Bus>,
            finish: finish::<Bus>,
            context: super::super::memory::empty_context(),
            memory_runtime: super::super::memory::Runtime {
                cpu: std::ptr::null_mut(),
                bus: std::ptr::null_mut(),
                fast: std::ptr::null(),
            },
            cpu,
            bus,
            budget: ticks,
            executed: 0,
            native: 0,
            guards: 0,
            stopped: false,
            pending_native: false,
            pending_opcode: 0,
            fetched_mismatch: None,
        };
        runtime.memory_runtime.cpu = runtime.cpu;
        runtime.memory_runtime.bus = runtime.bus;
        runtime.context = super::super::memory::context(&mut runtime.memory_runtime);
        // SAFETY: emitter preserves x19..x30 and a 16-byte-aligned stack; accesses only
        // callback-provided registers 0..15. Its callback slots are repr(C), pointer-sized,
        // and initialized for this bus monomorphization on every entry. The code owner
        // remains borrowed for the whole call. C callbacks must not unwind (as with other
        // extern C boundaries, a bus panic aborts rather than crossing generated frames).
        unsafe {
            let call: unsafe extern "C" fn(*mut Runtime<'_, Bus>) =
                std::mem::transmute(code.entry_pointer());
            call(&mut runtime);
        }
        #[cfg(any(test, feature = "jit-preview"))]
        let mut changed_native = 0;
        if let Some(opcode) = runtime.fetched_mismatch {
            let native = executor.execute(runtime.cpu, runtime.bus, opcode);
            #[cfg(any(test, feature = "jit-preview"))]
            {
                changed_native = u64::from(native);
            }
            #[cfg(feature = "jit-preview")]
            jgenesis_common::jit::record_opcode(opcode, native);
            if !native {
                Bus::opcode_table().decode(opcode)(runtime.cpu, opcode, runtime.bus);
            }
            runtime.complete();
        }
        #[cfg(any(test, feature = "jit-preview"))]
        {
            executor.native_instructions += runtime.native;
            executor.native_blocks += 1;
            executor.block_instructions += runtime.native;
            executor.budget_exits += u64::from(runtime.executed == ticks);
            executor.stop_exits += u64::from(runtime.stopped);
            executor.interpreted_instructions += runtime.executed - runtime.native - changed_native;
            executor.guard_exits += runtime.guards;
        }
        #[cfg(feature = "jit-preview")]
        {
            use jgenesis_common::jit::{ExitKind, record_exit};
            jgenesis_common::jit::record_dispatch(jgenesis_common::jit::DispatchKind::Guarded, runtime.native);
            if runtime.guards != 0 {
                record_exit(ExitKind::Guard);
            }
            if runtime.executed == ticks {
                record_exit(ExitKind::Budget);
            }
            if runtime.stopped {
                record_exit(ExitKind::Stop);
            }
        }
        if runtime.fetched_mismatch.is_some() {
            // Changed bytes invalidate this prediction. A different branch path
            // merely exits the block: all its per-instruction guards remain valid
            // when execution returns to the original path on another slice.
            executor.remove_block(entry_pc);
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
        assert_eq!(&code[..2], &[0xA9BB53F3, 0xA9015BF5]);
        assert_eq!(&code[code.len() - 2..], &[0xA8C553F3, 0xD65F03C0]);
        assert_eq!(code.iter().filter(|&&word| word == 0xD65F03C0).count(), 1);
        let mut branches = 0;
        for (i, &word) in code.iter().enumerate() {
            if word & 0xFF000000 == 0xB4000000 || word & 0xFF000000 == 0x35000000 {
                assert_eq!(i + ((word >> 5) & 0x7FFFF) as usize, code.len() - 6);
                branches += 1;
            }
        }
        assert_eq!(branches, 2);
        assert!(super::emit(&[(0, 0xE101), (2, 0xFFFF)]).is_none());
        assert!(super::emit(&[(0, 0xE101)]).is_none());
    }
}
