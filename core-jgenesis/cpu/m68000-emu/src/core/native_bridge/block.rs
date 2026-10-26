//! Stack-owned block execution bridge. No references or host pointers escape the
//! synchronous generated call; CPU and bus remain available to each timing callback.
use super::*;
#[repr(C)]
struct Batch<'a, 'cpu, 'bus, B: BusInterface, Before, After> {
    prepare: unsafe extern "C" fn(
        *mut Batch<'a, 'cpu, 'bus, B, Before, After>,
        u32,
        u16,
    ) -> *mut Context,
    finish: unsafe extern "C" fn(*mut Batch<'a, 'cpu, 'bus, B, Before, After>) -> u32,
    bridge: Runtime<'a, 'cpu, 'bus, B>,
    context: Context,
    before: &'a mut Before,
    after: &'a mut After,
    pc: u32,
    #[cfg(feature = "jit-preview")]
    opcode: u16,
    steps: u32,
    #[cfg(any(test, feature = "jit-preview"))]
    native: u64,
    stopped: bool,
}
impl<B: BusInterface, Before, After> Batch<'_, '_, '_, B, Before, After>
where
    Before: FnMut(&mut M68000, &mut B),
    After: FnMut(&mut M68000, &mut B, u32, u32) -> bool,
{
    fn complete(&mut self, cycles: u32) -> bool {
        self.steps += 1;
        let e = &mut self.bridge.executor;
        let keep = (self.after)(e.cpu, e.bus, self.pc, cycles);
        self.stopped = !keep;
        !keep
    }
    fn event(&mut self, cycles: u32) -> *mut Context {
        self.complete(cycles);
        self.stopped = true;
        std::ptr::null_mut()
    }
}
unsafe extern "C" fn prepare<B: BusInterface, Before, After>(
    p: *mut Batch<'_, '_, '_, B, Before, After>,
    pc: u32,
    expected: u16,
) -> *mut Context
where
    Before: FnMut(&mut M68000, &mut B),
    After: FnMut(&mut M68000, &mut B, u32, u32) -> bool,
{
    let r = unsafe { &mut *p };
    if r.bridge.executor.cpu.pc() != pc || r.bridge.executor.cpu.next_opcode() != expected {
        jgenesis_common::jit::record_exit(jgenesis_common::jit::ExitKind::Guard);
        return std::ptr::null_mut();
    }
    r.pc = pc;
    (r.before)(r.bridge.executor.cpu, r.bridge.executor.bus);
    if r.bridge.executor.bus.reset() {
        r.bridge.executor.cpu.reset(r.bridge.executor.bus);
        return r.event(RESET_CYCLES);
    }
    if r.bridge.executor.bus.halt() || r.bridge.executor.cpu.registers.frozen {
        return r.event(1);
    }
    if let Some(cycles) = r.bridge.executor.begin_execute() {
        return r.event(cycles);
    }
    match r.bridge.executor.prepare_decoded() {
        Err(e) => {
            let cycles = r.bridge.executor.handle_exception(e);
            r.event(cycles)
        }
        Ok((pc, opcode, instruction)) if opcode != expected => {
            let result = r.bridge.executor.execute_decoded(pc, opcode, instruction);
            let cycles = match result {
                Ok(c) => c,
                Err(e) => r.bridge.executor.handle_exception(e),
            };
            r.event(cycles)
        }
        Ok((_, opcode, _)) => {
            #[cfg(feature = "jit-preview")]
            {
                r.opcode = opcode;
            }
            r.bridge.exception = None;
            r.context.error = 0;
            r.context.ccr = u8::from(r.bridge.executor.cpu.registers.ccr).into();
            r.context.cycles = native_cycles(opcode).expect("learned legal instruction");
            r.context.supervisor = u32::from(r.bridge.executor.cpu.registers.supervisor_mode);
            r.context.allow_tas_writes = u32::from(r.bridge.executor.cpu.allow_tas_writes);
            std::ptr::from_mut(&mut r.context)
        }
    }
}
unsafe extern "C" fn finish<B: BusInterface, Before, After>(
    p: *mut Batch<'_, '_, '_, B, Before, After>,
) -> u32
where
    Before: FnMut(&mut M68000, &mut B),
    After: FnMut(&mut M68000, &mut B, u32, u32) -> bool,
{
    let r = unsafe { &mut *p };
    #[cfg(any(test, feature = "jit-preview"))]
    {
        r.native += 1;
    }
    #[cfg(feature = "jit-preview")]
    jgenesis_common::jit::record_opcode(r.opcode, true);
    let exception = r.bridge.exception.take();
    let exceptional = exception.is_some();
    let cycles = match exception {
        Some(e) => r.bridge.executor.handle_exception(e),
        None => r.context.cycles,
    };
    let stop = r.complete(cycles) || exceptional;
    if stop {
        r.stopped = true;
        jgenesis_common::jit::record_exit(jgenesis_common::jit::ExitKind::Stop);
    }
    u32::from(stop)
}
pub(in super::super) fn run<B: BusInterface, Before, After>(
    cpu: &mut M68000,
    bus: &mut B,
    native: &mut crate::native::NativeExecutor,
    before: &mut Before,
    after: &mut After,
) -> u32
where
    Before: FnMut(&mut M68000, &mut B),
    After: FnMut(&mut M68000, &mut B, u32, u32) -> bool,
{
    let Some(entry) = native.block_entry(cpu.pc()) else { return 0 };
    let mut executor = InstructionExecutor::new(cpu, bus);
    let mut bridge = Runtime { executor: &mut executor, exception: None };
    let context = make_context(&mut bridge, 0);
    let mut batch = Batch {
        prepare: prepare::<B, Before, After>,
        finish: finish::<B, Before, After>,
        bridge,
        context,
        before,
        after,
        pc: 0,
        #[cfg(feature = "jit-preview")]
        opcode: 0,
        steps: 0,
        #[cfg(any(test, feature = "jit-preview"))]
        native: 0,
        stopped: false,
    };
    batch.context.opaque = std::ptr::from_mut(&mut batch.bridge).cast();
    // SAFETY: validated block ABI preserves x19..x27 and SP, embeds no guest
    // pointers, and returns before this runtime or any borrowed code can expire.
    unsafe {
        let run: unsafe extern "C" fn(*mut Batch<'_, '_, '_, B, Before, After>) =
            std::mem::transmute(entry);
        run(&mut batch);
    }
    #[cfg(any(test, feature = "jit-preview"))]
    native.count_block(batch.native);
    if batch.steps != 0 && !batch.stopped {
        jgenesis_common::jit::record_exit(jgenesis_common::jit::ExitKind::Budget);
    }
    batch.steps
}
