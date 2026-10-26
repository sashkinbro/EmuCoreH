//! QA-only runtime backend selection and truthful executed-opcode accounting.
//! No host state is stored in guest CPU structs or savestates.
use std::cell::Cell;
#[cfg(feature = "jit-preview")]
use std::cell::RefCell;
#[cfg(feature = "jit-preview")]
use std::fmt::Write;

#[derive(Clone, Copy, Debug)]
pub enum Cpu {
    MainM68k,
    SubM68k,
    MasterSh2,
    SlaveSh2,
}
#[cfg(feature = "jit-preview")]
impl Cpu {
    fn index(self) -> usize {
        self as usize
    }
    fn name(self) -> &'static str {
        match self {
            Self::MainM68k => "m68k-main",
            Self::SubM68k => "m68k-sub",
            Self::MasterSh2 => "sh2-master",
            Self::SlaveSh2 => "sh2-slave",
        }
    }
}
#[derive(Clone, Copy)]
pub enum ExitKind {
    Guard,
    Budget,
    Stop,
}
#[derive(Clone, Copy)]
pub enum CacheEvent {
    Hit,
    Compile,
    Evict,
    Failure,
}
#[cfg(feature = "jit-preview")]
#[derive(Clone, Copy)]
pub enum DispatchKind {
    Leaf,
    Guarded,
    Fast,
    Slice,
}
#[cfg(feature = "jit-preview")]
struct Counts {
    opcodes: Box<[[u64; 2]]>,
    exits: [u64; 3],
    cache: [u64; 4],
    dispatch: [u64; 4],
}
#[cfg(feature = "jit-preview")]
impl Default for Counts {
    fn default() -> Self {
        Self { opcodes: vec![[0; 2]; 65536].into_boxed_slice(), exits: [0; 3], cache: [0; 4], dispatch: [0; 4] }
    }
}
thread_local! {
    static ENABLED: Cell<bool> = const { Cell::new(false) };
    #[cfg(feature="jit-preview")]
    static CURRENT: Cell<Option<Cpu>> = const { Cell::new(None) };
    static EPOCH: Cell<u64> = const { Cell::new(0) };
    #[cfg(feature="jit-preview")]
    static COUNTS: RefCell<[Counts; 4]> = RefCell::new(std::array::from_fn(|_| Counts::default()));
}
pub fn enabled() -> bool {
    ENABLED.get()
}
pub fn set_enabled(enabled: bool) {
    ENABLED.set(enabled);
}
pub fn epoch() -> u64 {
    EPOCH.get()
}
/// Invalidate code caches after content/state lifetime changes, preserving counters.
pub fn invalidate() {
    EPOCH.set(EPOCH.get().wrapping_add(1));
}
pub fn reset() {
    invalidate();
    clear_counters();
}
#[cfg(feature = "jit-preview")]
pub fn clear_counters() {
    COUNTS.with_borrow_mut(|v| *v = std::array::from_fn(|_| Counts::default()));
}
#[cfg(feature = "jit-preview")]
pub fn with_cpu<T>(cpu: Cpu, f: impl FnOnce() -> T) -> T {
    struct Restore(Option<Cpu>);
    impl Drop for Restore {
        fn drop(&mut self) {
            CURRENT.set(self.0);
        }
    }
    let _restore = Restore(CURRENT.replace(Some(cpu)));
    f()
}
#[cfg(feature = "jit-preview")]
pub fn record_opcode(opcode: u16, native: bool) {
    if let Some(cpu) = CURRENT.get() {
        COUNTS.with_borrow_mut(|v| {
            v[cpu.index()].opcodes[usize::from(opcode)][usize::from(!native)] += 1
        });
    }
}
#[cfg(feature = "jit-preview")]
pub fn record_exit(kind: ExitKind) {
    if let Some(cpu) = CURRENT.get() {
        COUNTS.with_borrow_mut(|v| v[cpu.index()].exits[kind as usize] += 1);
    }
}
/// Count an executed native block prefix in one profile borrow.
#[cfg(feature = "jit-preview")]
pub fn record_native_run(opcodes: &[u16]) {
    if let Some(cpu) = CURRENT.get() {
        COUNTS.with_borrow_mut(|v| {
            let bins = &mut v[cpu.index()].opcodes;
            for &opcode in opcodes {
                bins[usize::from(opcode)][0] += 1;
            }
        });
    }
}
#[cfg(feature = "jit-preview")]
pub fn record_cache_event(event: CacheEvent) {
    if let Some(cpu) = CURRENT.get() {
        COUNTS.with_borrow_mut(|v| v[cpu.index()].cache[event as usize] += 1);
    }
}
#[cfg(feature = "jit-preview")]
pub fn record_dispatch(kind: DispatchKind, count: u64) {
    if let Some(cpu) = CURRENT.get() {
        COUNTS.with_borrow_mut(|v| v[cpu.index()].dispatch[kind as usize] += count);
    }
}
/// Sparse opcode histogram: each tuple is [opcode, native executions, fallback executions].
#[cfg(feature = "jit-preview")]
pub fn snapshot_json() -> String {
    COUNTS.with_borrow(|counts| {
        let mut out = format!("{{\"schema\":1,\"backend\":\"{}\",\"cpus\":[", if enabled() { "native" } else { "interpreter" });
        for (i, cpu) in [Cpu::MainM68k, Cpu::SubM68k, Cpu::MasterSh2, Cpu::SlaveSh2].into_iter().enumerate() {
            if i != 0 { out.push(','); }
            let c = &counts[i];
            let native: u64 = c.opcodes.iter().map(|n| n[0]).sum();
            let fallback: u64 = c.opcodes.iter().map(|n| n[1]).sum();
            let _ = write!(out, "{{\"cpu\":\"{}\",\"native\":{native},\"fallback\":{fallback},\"guard_exits\":{},\"budget_exits\":{},\"stop_exits\":{},\"cache_hits\":{},\"cache_compiles\":{},\"cache_evictions\":{},\"cache_failures\":{}", cpu.name(), c.exits[0], c.exits[1], c.exits[2],c.cache[0],c.cache[1],c.cache[2],c.cache[3]);
            if matches!(cpu, Cpu::MasterSh2 | Cpu::SlaveSh2) {
                let _ = write!(out, ",\"dispatch\":{{\"leaf\":{},\"guarded\":{},\"fast\":{},\"slices\":{}}}", c.dispatch[0], c.dispatch[1], c.dispatch[2], c.dispatch[3]);
            }
            out.push_str(",\"opcodes\":[");
            for (j, (opcode, n)) in c.opcodes.iter().enumerate().filter(|(_, n)| **n != [0, 0]).enumerate() {
                if j != 0 { out.push(','); }
                let _ = write!(out, "[{opcode},{},{}]", n[0], n[1]);
            }
            out.push_str("]}");
        }
        out.push_str("]}"); out
    })
}
#[cfg(all(test, feature = "jit-preview"))]
mod tests {
    use super::*;
    #[test]
    fn nested_cpu_scopes_restore_attribution() {
        reset();
        with_cpu(Cpu::MainM68k, || {
            record_opcode(1, true);
            with_cpu(Cpu::SlaveSh2, || record_opcode(2, false));
            record_opcode(3, false);
        });
        COUNTS.with_borrow(|v| {
            assert_eq!(v[0].opcodes[1], [1, 0]);
            assert_eq!(v[0].opcodes[3], [0, 1]);
            assert_eq!(v[3].opcodes[2], [0, 1]);
        });
        assert!(CURRENT.get().is_none());
    }

    #[test]
    fn sh2_dispatch_counts_distinguish_leaf_wrappers_from_block_execution() {
        reset();
        with_cpu(Cpu::MasterSh2, || {
            record_dispatch(DispatchKind::Leaf, 1);
            record_dispatch(DispatchKind::Guarded, 2);
            record_dispatch(DispatchKind::Fast, 3);
            record_dispatch(DispatchKind::Slice, 4);
        });
        assert!(snapshot_json().contains("\"dispatch\":{\"leaf\":1,\"guarded\":2,\"fast\":3,\"slices\":4}"));
        clear_counters();
        assert!(snapshot_json().contains("\"dispatch\":{\"leaf\":0,\"guarded\":0,\"fast\":0,\"slices\":0}"));
    }
}

#[cfg(not(feature = "jit-preview"))]
#[inline(always)]
pub fn clear_counters() {}
#[cfg(not(feature = "jit-preview"))]
#[inline(always)]
pub fn with_cpu<T>(_: Cpu, f: impl FnOnce() -> T) -> T {
    f()
}
#[cfg(not(feature = "jit-preview"))]
#[inline(always)]
pub fn record_opcode(_: u16, _: bool) {}
#[cfg(not(feature = "jit-preview"))]
#[inline(always)]
pub fn record_exit(_: ExitKind) {}
#[cfg(not(feature = "jit-preview"))]
#[inline(always)]
pub fn record_cache_event(_: CacheEvent) {}
#[cfg(not(feature = "jit-preview"))]
#[inline(always)]
pub fn record_native_run(_: &[u16]) {}
