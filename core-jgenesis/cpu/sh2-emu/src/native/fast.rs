//! Scoped direct-fetch blocks. Cache hits and explicitly mapped side-effect-free
//! fetches execute with no Rust boundary between instructions. Misses exit before
//! performing a guest fetch; the ordinary fetch path retains all observable work.
#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
pub(super) struct Compiled {
    pub code: jgenesis_common::native_code::PublishedCode,
    #[cfg(any(test, feature = "jit-preview"))]
    pub opcodes: Vec<u16>,
}
pub(super) fn load_x(field: u32, r: u32) -> u32 {
    0xF9400380 | ((field / 8) << 10) | r
}
pub(super) fn constant(c: &mut Vec<u32>, r: u32, v: u32) {
    c.push(0x52800000 | ((v & 65535) << 5) | r);
    if v >> 16 != 0 {
        c.push(0x72A00000 | ((v >> 16) << 5) | r);
    }
}
pub(super) fn branch(c: &mut Vec<u32>, list: &mut Vec<usize>, instruction: u32) {
    list.push(c.len());
    c.push(instruction);
}
pub(super) fn patch(c: &mut [u32], at: usize, target: usize, kind: u32) {
    let delta = (target as i32 - at as i32) as u32;
    c[at] |= if kind == 26 {
        delta & 0x03FFFFFF
    } else if kind == 14 {
        (delta & 0x3FFF) << 5
    } else {
        (delta & 0x7FFFF) << 5
    };
}
fn inline_body(opcode: u16) -> Option<Vec<u32>> {
    let mut body = super::block::inline_body(opcode)?;
    // The granted scalar cycle view has no observer/synchronization side effects.
    // Replace cycle-only calls with equal-length direct additions. Typed data/MMIO
    // callbacks remain unchanged and synchronize the current instruction context.
    for i in 0..body.len().saturating_sub(3) {
        if body[i] == 0xF9400A60
            && body[i + 2] == 0xF9402A70
            && body[i + 3] == 0xD63F0200
            && body[i + 1] & 0xFFE0001F == 0x52800001
        {
            let cycles = (body[i + 1] >> 5) & 65535;
            body[i..i + 4].copy_from_slice(&[
                load_x(32, 9),
                0xF940012A,
                0x9100014A | (cycles << 10),
                0xF900012A,
            ]);
        }
    }
    Some(body)
}

pub(super) fn emit(trace: &[(u32, u16)]) -> Option<Vec<u32>> {
    if trace.len() < 2
        || trace.len() > 16
        || trace.iter().any(|&(pc, _)| pc & 1 != 0 || pc >> 29 > 1)
    {
        return None;
    }
    let mut c =
        vec![0xA9BA53F3, 0xA9015BF5, 0xA90263F7, 0xA9036BF9, 0xA90473FB, 0xA9057BFD, 0xAA0003FC];
    let mut exits = Vec::new();
    let mut guards = Vec::new();
    let mut stops = Vec::new();
    let restart = c.len();
    let mut position = 0;
    while position < trace.len() {
        let length = super::fused::length(&trace[position..]);
        if length >= 2 {
            super::fused::emit(
                &trace[position..position + length],
                &mut c,
                &mut exits,
                &mut guards,
            )?;
            position += length;
            continue;
        }
        let (pc, opcode) = trace[position];
        position += 1;
        // Every instruction rechecks its live PC and fetched opcode. Data callbacks may
        // alter code/cache contents, so no prior fetch observation authorizes later ones.
        c.extend([load_x(8, 9), 0xB940012A]);
        constant(&mut c, 11, pc);
        c.push(0x6B0B015F);
        // A conditional branch can legitimately leave the learned fallthrough
        // path. Preserve the code unless fetched instruction bytes changed.
        branch(&mut c, &mut exits, 0x54000001); // b.ne exit
        if pc >> 29 == 0 {
            c.extend([load_x(48, 9), 0x39400129]);
            branch(&mut c, &mut exits, 0x34000009);
            let index = (pc >> 4) & 63;
            let tag = (pc & 0x1FFFFFFF) >> 10;
            let mut found = Vec::new();
            for way in (0..4).rev() {
                let mut next = Vec::new();
                c.extend([load_x(104 + way * 8, 9), 0xF9400129]);
                let tb = c.len();
                c.push(0x36000009 | ((index & 31) << 19) | ((index >> 5) << 31));
                c.extend([load_x(72 + way * 8, 9), 0xB940012A | (index << 10)]);
                constant(&mut c, 11, tag);
                c.push(0x6B0B015F);
                branch(&mut c, &mut next, 0x54000001);
                let offset = (way << 10) | (index << 4) | (pc & 14);
                c.extend([load_x(56, 9), 0x7940012A | ((offset / 2) << 10)]);
                constant(&mut c, 11, u32::from(opcode));
                c.push(0x6B0B015F);
                branch(&mut c, &mut guards, 0x54000001);
                c.extend([load_x(64, 9), 0x3940012A | (index << 10)]);
                let (and, or) = match way {
                    0 => (0xC7, 0),
                    1 => (0xF9, 0x20),
                    2 => (0xFE, 0x14),
                    _ => (0xFF, 0x0B),
                };
                constant(&mut c, 11, and);
                c.push(0x0A0B014A);
                constant(&mut c, 11, or);
                c.push(0x2A0B014A);
                c.push(0x3900012A | (index << 10));
                branch(&mut c, &mut found, 0x14000000);
                let label = c.len();
                patch(&mut c, tb, label, 14);
                for at in next {
                    patch(&mut c, at, label, 19);
                }
            }
            branch(&mut c, &mut exits, 0x14000000);
            let label = c.len();
            for at in found {
                patch(&mut c, at, label, 26);
            }
        } else {
            constant(&mut c, 10, pc);
            c.push(0xB940AB8B); // fetch_start +168
            c.push(0x6B0B015F);
            branch(&mut c, &mut exits, 0x54000003); // below start
            c.push(0xB940AF8B);
            c.push(0x6B0B015F);
            branch(&mut c, &mut exits, 0x54000002); // >= end
            c.push(0xB940AB8B);
            c.push(0x4B0B014A);
            c.push(load_x(160, 9));
            c.push(0x786A492A);
            constant(&mut c, 11, u32::from(opcode));
            c.push(0x6B0B015F);
            branch(&mut c, &mut guards, 0x54000001);
            c.extend([load_x(32, 9), 0xF940012A, load_x(176, 11), 0x8B0B014A, 0xF900012A]);
        }
        // Normal preparation, expressed only through typed pointer fields.
        c.extend([
            load_x(16, 9),
            0xB940012A,
            load_x(8, 11),
            0xB900016A,
            0x1100094A,
            0xB900012A,
            load_x(24, 9),
            0x3900013F,
        ]);
        constant(&mut c, 9, pc);
        constant(&mut c, 10, u32::from(opcode));
        c.extend([0xB9009B89, 0xB9009F8A, load_x(0, 0)]);
        c.extend(inline_body(opcode)?);
        c.extend([
            load_x(136, 9),
            0x91000529,
            0xF9004789,
            load_x(32, 9),
            0xF940012A,
            0x9100054A,
            0xF900012A,
            load_x(40, 11),
            0xF940016B,
            0xEB0B015F,
        ]);
        branch(&mut c, &mut stops, 0x54000002);
        c.extend([load_x(136, 9), load_x(144, 10), 0xEB0A013F]);
        branch(&mut c, &mut exits, 0x54000002);
    }
    // Link a complete trace back to its own entry only after checking the actual
    // architectural PC. Every iteration still executes all fetch/data/stop guards.
    c.extend([load_x(8, 9), 0xB940012A]);
    constant(&mut c, 11, trace[0].0);
    c.push(0x6B0B015F);
    let repeat = c.len();
    c.push(0x54000000);
    patch(&mut c, repeat, restart, 19);
    branch(&mut c, &mut exits, 0x14000000);
    let guard = c.len();
    c.extend([0x52800029, 0xB900BB89]);
    let done_guard = c.len();
    c.push(0x14000000);
    let stop = c.len();
    c.extend([0x52800049, 0xB900BB89]);
    let end = c.len();
    patch(&mut c, done_guard, end, 26);
    for at in exits {
        let kind = if c[at] & 0xFC000000 == 0x14000000 { 26 } else { 19 };
        patch(&mut c, at, end, kind);
    }
    for at in guards {
        patch(&mut c, at, guard, 19);
    }
    for at in stops {
        patch(&mut c, at, stop, 19);
    }
    c.extend([0xA9457BFD, 0xA94473FB, 0xA9436BF9, 0xA94263F7, 0xA9415BF5, 0xA8C653F3, 0xD65F03C0]);
    Some(c)
}

#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
#[repr(C)]
pub(super) struct State {
    context: *mut super::Context,
    pc: *mut u32,
    next_pc: *mut u32,
    delay: *mut bool,
    cycles: *mut u64,
    limit: *const u64,
    enabled: *const bool,
    ram: *const u16,
    lru: *mut u8,
    tags: [*const u32; 4],
    valid: [*const u64; 4],
    executed: u64,
    budget: u64,
    pub last_pc: u32,
    pub last_opcode: u32,
    fetch_words: *const u16,
    fetch_start: u32,
    fetch_end: u32,
    fetch_cycles: u64,
    exit_reason: u32,
}
#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
pub(super) fn synchronize(cpu: &mut crate::Sh2, state: *const State) {
    if !state.is_null() {
        let s = unsafe { &*state };
        cpu.data_ctx =
            crate::bus::AccessContext::Data { pc: s.last_pc, opcode: s.last_opcode as u16 };
    }
}
#[cfg(all(
    target_arch = "aarch64",
    target_endian = "little",
    any(target_os = "android", target_os = "linux")
))]
pub(super) fn run<B: crate::bus::BusInterface>(
    executor: &mut super::NativeExecutor,
    cpu: &mut crate::Sh2,
    bus: &mut B,
    ticks: u64,
) -> Option<(u64, bool)> {
    if cpu.trace_log_enabled && log::log_enabled!(log::Level::Trace) {
        return None;
    }
    let entry_pc = cpu.pc();
    executor.cache_clock = executor.cache_clock.wrapping_add(1);
    if let Some((age, _, _)) = executor.block_metadata.get_mut(&entry_pc) {
        *age = executor.cache_clock;
    }
    let code = executor.fast_blocks.get(&entry_pc)?;
    // SAFETY: implementers explicitly promise stable scoped pointers, pure cycle/stop
    // operations and side-effect-free direct fetches. None retains the normal path.
    let view = unsafe { bus.native_bus_view() }?;
    let cache = cpu.cache.native_view();
    let mut memory = super::memory::Runtime { cpu, bus, fast: std::ptr::null() };
    let mut context = super::memory::context(&mut memory);
    let mut state = State {
        context: &mut context,
        pc: &mut cpu.registers.pc,
        next_pc: &mut cpu.registers.next_pc,
        delay: &mut cpu.registers.next_op_in_delay_slot,
        cycles: view.cycle_counter,
        limit: view.cycle_limit,
        enabled: cache.enabled,
        ram: cache.ram,
        lru: cache.lru,
        tags: cache.tags,
        valid: cache.valid,
        executed: 0,
        budget: ticks,
        last_pc: 0,
        last_opcode: 0,
        fetch_words: view.fetch_words,
        fetch_start: view.fetch_start,
        fetch_end: view.fetch_end,
        fetch_cycles: view.fetch_cycles,
        exit_reason: 0,
    };
    #[allow(unused_assignments)]
    {
        memory.fast = &state;
    }
    // SAFETY: all pointers are live for this synchronous call; generated code guards
    // actual PC/opcode/cache validity and saves every AAPCS64 callee-saved register.
    unsafe {
        let call: unsafe extern "C" fn(*mut State) = std::mem::transmute(code.code.entry_pointer());
        call(&mut state);
    }
    if state.executed != 0 {
        synchronize(cpu, &state);
    }
    #[cfg(feature = "jit-preview")]
    {
        let length = code.opcodes.len();
        jgenesis_common::jit::record_dispatch(jgenesis_common::jit::DispatchKind::Fast, state.executed);
        for _ in 0..state.executed as usize / length {
            jgenesis_common::jit::record_native_run(&code.opcodes);
        }
        jgenesis_common::jit::record_native_run(&code.opcodes[..state.executed as usize % length]);
    }
    let stopped = state.exit_reason == 2;
    #[cfg(any(test, feature = "jit-preview"))]
    {
        executor.fast_instructions += state.executed;
        executor.native_instructions += state.executed;
        executor.block_instructions += state.executed;
        executor.native_blocks += 1;
        executor.stop_exits += u64::from(stopped);
        executor.budget_exits += u64::from(state.executed == ticks);
    }
    #[cfg(feature = "jit-preview")]
    {
        use jgenesis_common::jit::{ExitKind, record_exit};
        if stopped {
            record_exit(ExitKind::Stop);
        }
        if state.executed == ticks {
            record_exit(ExitKind::Budget);
        }
    }
    if state.exit_reason == 1 {
        #[cfg(any(test, feature = "jit-preview"))]
        {
            executor.guard_exits += 1;
        }
        executor.remove_block(entry_pc);
        #[cfg(feature = "jit-preview")]
        jgenesis_common::jit::record_exit(jgenesis_common::jit::ExitKind::Guard);
    }
    Some((state.executed, stopped))
}
#[cfg(test)]
mod tests {
    #[test]
    fn direct_fetch_blocks_emit_only_inlined_bodies_and_no_prepare_calls() {
        let c = super::emit(&[(0x26000100, 0xE101), (0x26000102, 0x7101)]).unwrap();
        assert!(!c.contains(&0xD63F0200));
        assert_eq!(c.iter().filter(|&&v| v == 0xD65F03C0).count(), 1);
        assert!(super::emit(&[(0x100, 0xE101), (0x102, 0x7101)]).is_some());
    }
}
