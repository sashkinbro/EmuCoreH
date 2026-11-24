//! Lockstep differential verification of the block engine against the interpreter.
//!
//! Every `execute()` slice runs twice:
//! 1. the block engine runs on the real bus wrapped in a `RecBus`, which forwards every bus call
//!    and logs it (address, size, context, value, cycle counter before and after), plus a
//!    checkpoint after every executed block (instructions left, full register file, counter,
//!    `data_ctx`);
//! 2. a clone of the CPU taken before step 1 (host-only idle-loop state included, so both sides
//!    make the same idle-loop decisions) runs the interpreter on a `ReplayBus`, which answers every
//!    call from the log and fails on the first call that differs in kind, address, size, value,
//!    context or cycle counter. Its per-instruction hook compares the register file, counter and
//!    `data_ctx` at every block checkpoint.
//!
//! At the end of the slice the cycle counters must match, the whole log must have been consumed
//! and (every `deep_every` slices) the complete serialized CPU states must be identical. Any
//! divergence panics with a description.

use super::Jit;
use crate::bus::{AccessContext, BusInterface, OpSize};
use crate::instructions::OpcodeTable;
use crate::registers::Sh2Registers;
use crate::{ExecutionBody, Sh2};
use std::cell::RefCell;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

static VERIFY: AtomicBool = AtomicBool::new(false);
static DEEP_EVERY: AtomicU64 = AtomicU64::new(1);

/// Runs every block-engine slice in lockstep with the interpreter (host testing only; slow)
pub fn set_jit_verify(enabled: bool) {
    VERIFY.store(enabled, Ordering::Relaxed);
}

/// Compare the complete serialized CPU state every `n` verified slices (0 = never)
pub fn set_jit_verify_deep_every(n: u64) {
    DEEP_EVERY.store(n, Ordering::Relaxed);
}

#[inline(always)]
pub(super) fn verify_enabled() -> bool {
    VERIFY.load(Ordering::Relaxed)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Ctx(u8, u32, u16);

fn ctx_key(ctx: AccessContext) -> Ctx {
    match ctx {
        AccessContext::Fetch => Ctx(0, 0, 0),
        AccessContext::Data { pc, opcode } => Ctx(1, pc, opcode),
        AccessContext::InterruptVector => Ctx(2, 0, 0),
        AccessContext::Dma { channel } => Ctx(3, channel as u32, 0),
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Query {
    Reset,
    InterruptLevel,
    Dreq0,
    Dreq1,
    AckDreq1,
    SerialRx,
    SerialTx,
    ReadSupported,
    WriteSupported,
    ReadDeadline,
    ReplayRead,
    WriteIsNoop,
    OtherQuiescent,
    AdvanceOther,
}

#[derive(Debug, Clone)]
enum Ev {
    Read { size: u8, addr: u32, ctx: Ctx, value: u32, before: u64, after: u64 },
    Line { addr: u32, ctx: Ctx, words: [u16; 8], before: u64, after: u64 },
    Write { size: u8, addr: u32, value: u32, ctx: Ctx, before: u64, after: u64 },
    Query { q: Query, args: [u64; 3], result: u64 },
    Check { ticks_left: u64, regs: Box<Sh2Registers>, counter: u64, ctx: Ctx },
}

#[derive(Clone, Copy)]
struct VTable {
    read: [fn(*mut (), u32, AccessContext) -> u32; 3],
    line: fn(*mut (), u32, AccessContext) -> [u16; 8],
    write: [fn(*mut (), u32, u32, AccessContext); 3],
    reset: fn(*const ()) -> bool,
    level: fn(*const ()) -> u8,
    dreq0: fn(*const ()) -> bool,
    dreq1: fn(*const ()) -> bool,
    ack1: fn(*mut ()),
    rx: fn(*mut ()) -> Option<u8>,
    tx: fn(*mut (), u8),
    inc: fn(*mut (), u64),
    counter: fn(*const ()) -> u64,
    limit: fn(*const ()) -> u64,
    read_supported: fn(*const (), u32, u8) -> bool,
    write_supported: fn(*const (), u32, u8) -> bool,
    deadline: fn(*const (), u32, u8) -> Option<u64>,
    replay_read: fn(*mut (), u32, u8, u64) -> Option<u32>,
    write_noop: fn(*mut (), u32, u8, u32) -> bool,
    other_quiescent: fn(*mut ()) -> bool,
    advance_other: fn(*mut (), u32, u8, u64),
}

// SAFETY (all entries): the pointer is the `&mut B` the `RecBus` was created from, which outlives
// the `RecBus` and is not used by anything else meanwhile
fn vtable<B: BusInterface>() -> VTable {
    fn b<'a, B>(p: *mut ()) -> &'a mut B {
        unsafe { &mut *p.cast::<B>() }
    }
    fn s<'a, B>(p: *const ()) -> &'a B {
        unsafe { &*p.cast::<B>() }
    }
    VTable {
        read: [
            |p, a, c| b::<B>(p).read::<{ OpSize::BYTE }>(a, c),
            |p, a, c| b::<B>(p).read::<{ OpSize::WORD }>(a, c),
            |p, a, c| b::<B>(p).read::<{ OpSize::LONGWORD }>(a, c),
        ],
        line: |p, a, c| b::<B>(p).read_cache_line(a, c),
        write: [
            |p, a, v, c| b::<B>(p).write::<{ OpSize::BYTE }>(a, v, c),
            |p, a, v, c| b::<B>(p).write::<{ OpSize::WORD }>(a, v, c),
            |p, a, v, c| b::<B>(p).write::<{ OpSize::LONGWORD }>(a, v, c),
        ],
        reset: |p| s::<B>(p).reset(),
        level: |p| s::<B>(p).interrupt_level(),
        dreq0: |p| s::<B>(p).dma_request_0(),
        dreq1: |p| s::<B>(p).dma_request_1(),
        ack1: |p| b::<B>(p).acknowledge_dreq_1(),
        rx: |p| b::<B>(p).serial_rx(),
        tx: |p, v| b::<B>(p).serial_tx(v),
        inc: |p, n| b::<B>(p).increment_cycle_counter(n),
        counter: |p| s::<B>(p).idle_cycle_counter(),
        limit: |p| s::<B>(p).idle_cycle_limit(),
        read_supported: |p, a, z| s::<B>(p).idle_read_supported(a, z),
        write_supported: |p, a, z| s::<B>(p).idle_write_supported(a, z),
        deadline: |p, a, z| s::<B>(p).idle_read_deadline(a, z),
        replay_read: |p, a, z, c| b::<B>(p).idle_replay_read(a, z, c),
        write_noop: |p, a, z, v| b::<B>(p).idle_write_is_noop(a, z, v),
        other_quiescent: |p| b::<B>(p).idle_other_quiescent(),
        advance_other: |p, a, z, c| b::<B>(p).idle_advance_other(a, z, c),
    }
}

thread_local! {
    static LOG_POOL: RefCell<Vec<Vec<Ev>>> = const { RefCell::new(Vec::new()) };
}

/// Forwards every call to the wrapped bus and logs it
pub(crate) struct RecBus {
    inner: *mut (),
    vt: VTable,
    log: RefCell<Vec<Ev>>,
}

impl RecBus {
    fn new<B: BusInterface>(bus: &mut B) -> Self {
        let log = LOG_POOL.with_borrow_mut(Vec::pop).unwrap_or_default();
        Self { inner: std::ptr::from_mut(bus).cast(), vt: vtable::<B>(), log: RefCell::new(log) }
    }

    fn counter(&self) -> u64 {
        (self.vt.counter)(self.inner)
    }

    fn query(&self, q: Query, args: [u64; 3], result: u64) {
        self.log.borrow_mut().push(Ev::Query { q, args, result });
    }
}

fn size_index(size: u8) -> usize {
    usize::from(size.min(2))
}

impl BusInterface for RecBus {
    type DebugView<'a> = crate::debug::DummySh2Debugger;

    fn read<const SIZE: u8>(&mut self, address: u32, ctx: AccessContext) -> u32 {
        let before = self.counter();
        let value = (self.vt.read[size_index(SIZE)])(self.inner, address, ctx);
        let after = self.counter();
        self.log.get_mut().push(Ev::Read {
            size: SIZE,
            addr: address,
            ctx: ctx_key(ctx),
            value,
            before,
            after,
        });
        value
    }

    fn read_cache_line(&mut self, address: u32, ctx: AccessContext) -> [u16; 8] {
        let before = self.counter();
        let words = (self.vt.line)(self.inner, address, ctx);
        let after = self.counter();
        self.log.get_mut().push(Ev::Line { addr: address, ctx: ctx_key(ctx), words, before, after });
        words
    }

    fn write<const SIZE: u8>(&mut self, address: u32, value: u32, ctx: AccessContext) {
        let before = self.counter();
        (self.vt.write[size_index(SIZE)])(self.inner, address, value, ctx);
        let after = self.counter();
        self.log.get_mut().push(Ev::Write {
            size: SIZE,
            addr: address,
            value: value & OpSize::mask::<SIZE>(),
            ctx: ctx_key(ctx),
            before,
            after,
        });
    }

    fn reset(&self) -> bool {
        let r = (self.vt.reset)(self.inner);
        self.query(Query::Reset, [0; 3], r.into());
        r
    }

    fn interrupt_level(&self) -> u8 {
        let r = (self.vt.level)(self.inner);
        self.query(Query::InterruptLevel, [0; 3], r.into());
        r
    }

    fn dma_request_0(&self) -> bool {
        let r = (self.vt.dreq0)(self.inner);
        self.query(Query::Dreq0, [0; 3], r.into());
        r
    }

    fn dma_request_1(&self) -> bool {
        let r = (self.vt.dreq1)(self.inner);
        self.query(Query::Dreq1, [0; 3], r.into());
        r
    }

    fn acknowledge_dreq_1(&mut self) {
        (self.vt.ack1)(self.inner);
        self.query(Query::AckDreq1, [0; 3], 0);
    }

    fn serial_rx(&mut self) -> Option<u8> {
        let r = (self.vt.rx)(self.inner);
        self.query(Query::SerialRx, [0; 3], r.map_or(u64::MAX, u64::from));
        r
    }

    fn serial_tx(&mut self, value: u8) {
        (self.vt.tx)(self.inner, value);
        self.query(Query::SerialTx, [value.into(), 0, 0], 0);
    }

    fn increment_cycle_counter(&mut self, cycles: u64) {
        (self.vt.inc)(self.inner, cycles);
    }

    fn should_stop_execution(&self) -> bool {
        self.counter() >= (self.vt.limit)(self.inner)
    }

    crate::impl_sh2_opcode_table!(RecBus);

    const IDLE_LOOPS: bool = true;

    fn idle_cycle_counter(&self) -> u64 {
        self.counter()
    }

    fn idle_cycle_limit(&self) -> u64 {
        (self.vt.limit)(self.inner)
    }

    fn idle_read_supported(&self, address: u32, size: u8) -> bool {
        let r = (self.vt.read_supported)(self.inner, address, size);
        self.query(Query::ReadSupported, [address.into(), size.into(), 0], r.into());
        r
    }

    fn idle_write_supported(&self, address: u32, size: u8) -> bool {
        let r = (self.vt.write_supported)(self.inner, address, size);
        self.query(Query::WriteSupported, [address.into(), size.into(), 0], r.into());
        r
    }

    fn idle_read_deadline(&self, address: u32, size: u8) -> Option<u64> {
        let r = (self.vt.deadline)(self.inner, address, size);
        // u64::MAX is a valid deadline; encode None out of band
        let encoded = r.map_or(0, |d| d.wrapping_add(1));
        self.query(Query::ReadDeadline, [address.into(), size.into(), u64::from(r.is_none())], encoded);
        r
    }

    fn idle_replay_read(&mut self, address: u32, size: u8, counter: u64) -> Option<u32> {
        let r = (self.vt.replay_read)(self.inner, address, size, counter);
        self.query(
            Query::ReplayRead,
            [address.into(), size.into(), counter],
            r.map_or(u64::MAX, u64::from),
        );
        r
    }

    fn idle_write_is_noop(&mut self, address: u32, size: u8, value: u32) -> bool {
        let r = (self.vt.write_noop)(self.inner, address, size, value);
        self.query(Query::WriteIsNoop, [address.into(), size.into(), value.into()], r.into());
        r
    }

    fn idle_other_quiescent(&mut self) -> bool {
        let r = (self.vt.other_quiescent)(self.inner);
        self.query(Query::OtherQuiescent, [0; 3], r.into());
        r
    }

    fn idle_advance_other(&mut self, address: u32, size: u8, read_counter: u64) {
        (self.vt.advance_other)(self.inner, address, size, read_counter);
        self.query(Query::AdvanceOther, [address.into(), size.into(), read_counter], 0);
    }

    fn jit_checkpoint(&mut self, ticks_left: u64, cpu: &Sh2) {
        let counter = self.counter();
        self.log.get_mut().push(Ev::Check {
            ticks_left,
            regs: Box::new(cpu.registers.clone()),
            counter,
            ctx: ctx_key(cpu.data_ctx),
        });
    }
}

/// Answers every call from a `RecBus` log, recording the first mismatch
pub(crate) struct ReplayBus {
    log: Vec<Ev>,
    pos: std::cell::Cell<usize>,
    counter: u64,
    limit: u64,
    error: RefCell<Option<String>>,
    checks: u64,
}

impl ReplayBus {
    fn fail(&self, message: String) {
        let mut error = self.error.borrow_mut();
        if error.is_none() {
            *error = Some(message);
        }
    }

    /// The next logged event, skipping nothing: a checkpoint here means the interpreter made an
    /// access the block engine did not make before the end of a block
    fn next(&self, what: &str) -> Option<&Ev> {
        let pos = self.pos.get();
        let Some(ev) = self.log.get(pos) else {
            self.fail(format!("interpreter made an extra {what} (log has {} events)", self.log.len()));
            return None;
        };
        if let Ev::Check { ticks_left, .. } = ev {
            self.fail(format!(
                "interpreter made a {what} where the block engine ended a block (ticks left {ticks_left}, event {pos})"
            ));
            return None;
        }
        self.pos.set(pos + 1);
        Some(ev)
    }

    fn query(&self, q: Query, args: [u64; 3]) -> u64 {
        match self.next("query") {
            Some(&Ev::Query { q: lq, args: la, result }) if lq == q && la == args => result,
            Some(ev) => {
                self.fail(format!("expected query {q:?} {args:x?}, block engine made {ev:x?}"));
                0
            }
            None => 0,
        }
    }

    fn check_counter(&self, before: u64, ev: &Ev) {
        if before != self.counter {
            self.fail(format!(
                "cycle counter differs at {ev:x?}: interpreter {}, block engine {before}",
                self.counter
            ));
        }
    }

    /// Compares a block checkpoint at the current instruction boundary
    fn checkpoint(&mut self, cpu: &Sh2, ticks: u64) {
        while let Some(Ev::Check { ticks_left, regs, counter, ctx }) = self.log.get(self.pos.get())
        {
            if *ticks_left != ticks {
                if *ticks_left > ticks {
                    self.fail(format!(
                        "interpreter passed a block boundary: ticks {ticks}, checkpoint at {ticks_left}"
                    ));
                }
                return;
            }
            if **regs != cpu.registers || *counter != self.counter || *ctx != ctx_key(cpu.data_ctx)
            {
                self.fail(format!(
                    "state differs after block (ticks left {ticks}):\n  block engine {regs:x?} counter {counter} ctx {ctx:x?}\n  interpreter  {:x?} counter {} ctx {:x?}",
                    cpu.registers,
                    self.counter,
                    ctx_key(cpu.data_ctx)
                ));
            }
            self.checks += 1;
            self.pos.set(self.pos.get() + 1);
        }
    }
}

impl BusInterface for ReplayBus {
    type DebugView<'a> = crate::debug::DummySh2Debugger;

    fn read<const SIZE: u8>(&mut self, address: u32, ctx: AccessContext) -> u32 {
        let key = ctx_key(ctx);
        let (value, before, after, ev) = match self.next("read") {
            Some(ev @ &Ev::Read { size, addr, ctx: lctx, value, before, after })
                if size == SIZE && addr == address && lctx == key =>
            {
                (value, before, after, ev.clone())
            }
            Some(ev) => {
                let ev = ev.clone();
                self.fail(format!(
                    "interpreter read {} {address:08X} ({key:x?}), block engine made {ev:x?}",
                    OpSize::display::<SIZE>()
                ));
                return 0;
            }
            None => return 0,
        };
        self.check_counter(before, &ev);
        self.counter += after - before;
        value
    }

    fn read_cache_line(&mut self, address: u32, ctx: AccessContext) -> [u16; 8] {
        let key = ctx_key(ctx);
        let (words, before, after, ev) = match self.next("cache line read") {
            Some(ev @ &Ev::Line { addr, ctx: lctx, words, before, after })
                if addr == address && lctx == key =>
            {
                (words, before, after, ev.clone())
            }
            Some(ev) => {
                let ev = ev.clone();
                self.fail(format!(
                    "interpreter read line {address:08X} ({key:x?}), block engine made {ev:x?}"
                ));
                return [0; 8];
            }
            None => return [0; 8],
        };
        self.check_counter(before, &ev);
        self.counter += after - before;
        words
    }

    fn write<const SIZE: u8>(&mut self, address: u32, value: u32, ctx: AccessContext) {
        let key = ctx_key(ctx);
        let value = value & OpSize::mask::<SIZE>();
        let (before, after, ev) = match self.next("write") {
            Some(ev @ &Ev::Write { size, addr, value: lvalue, ctx: lctx, before, after })
                if size == SIZE && addr == address && lvalue == value && lctx == key =>
            {
                (before, after, ev.clone())
            }
            Some(ev) => {
                let ev = ev.clone();
                self.fail(format!(
                    "interpreter wrote {} {address:08X}={value:X} ({key:x?}), block engine made {ev:x?}",
                    OpSize::display::<SIZE>()
                ));
                return;
            }
            None => return,
        };
        self.check_counter(before, &ev);
        self.counter += after - before;
    }

    fn reset(&self) -> bool {
        self.query(Query::Reset, [0; 3]) != 0
    }

    fn interrupt_level(&self) -> u8 {
        self.query(Query::InterruptLevel, [0; 3]) as u8
    }

    fn dma_request_0(&self) -> bool {
        self.query(Query::Dreq0, [0; 3]) != 0
    }

    fn dma_request_1(&self) -> bool {
        self.query(Query::Dreq1, [0; 3]) != 0
    }

    fn acknowledge_dreq_1(&mut self) {
        self.query(Query::AckDreq1, [0; 3]);
    }

    fn serial_rx(&mut self) -> Option<u8> {
        let r = self.query(Query::SerialRx, [0; 3]);
        (r != u64::MAX).then_some(r as u8)
    }

    fn serial_tx(&mut self, value: u8) {
        self.query(Query::SerialTx, [value.into(), 0, 0]);
    }

    fn increment_cycle_counter(&mut self, cycles: u64) {
        self.counter += cycles;
    }

    fn should_stop_execution(&self) -> bool {
        self.counter >= self.limit
    }

    crate::impl_sh2_opcode_table!(ReplayBus);

    const IDLE_LOOPS: bool = true;

    fn idle_cycle_counter(&self) -> u64 {
        self.counter
    }

    fn idle_cycle_limit(&self) -> u64 {
        self.limit
    }

    fn idle_read_supported(&self, address: u32, size: u8) -> bool {
        self.query(Query::ReadSupported, [address.into(), size.into(), 0]) != 0
    }

    fn idle_write_supported(&self, address: u32, size: u8) -> bool {
        self.query(Query::WriteSupported, [address.into(), size.into(), 0]) != 0
    }

    fn idle_read_deadline(&self, address: u32, size: u8) -> Option<u64> {
        // The block engine's call recorded whether the answer was None in the third argument;
        // replay accepts either and reproduces the recorded answer
        let pos = self.pos.get();
        let none = matches!(
            self.log.get(pos),
            Some(Ev::Query { q: Query::ReadDeadline, args: [_, _, 1], .. })
        );
        let encoded = self.query(Query::ReadDeadline, [address.into(), size.into(), u64::from(none)]);
        if none { None } else { Some(encoded.wrapping_sub(1)) }
    }

    fn idle_replay_read(&mut self, address: u32, size: u8, counter: u64) -> Option<u32> {
        let r = self.query(Query::ReplayRead, [address.into(), size.into(), counter]);
        (r != u64::MAX).then_some(r as u32)
    }

    fn idle_write_is_noop(&mut self, address: u32, size: u8, value: u32) -> bool {
        self.query(Query::WriteIsNoop, [address.into(), size.into(), value.into()]) != 0
    }

    fn idle_other_quiescent(&mut self) -> bool {
        self.query(Query::OtherQuiescent, [0; 3]) != 0
    }

    fn idle_advance_other(&mut self, address: u32, size: u8, read_counter: u64) {
        self.query(Query::AdvanceOther, [address.into(), size.into(), read_counter]);
    }
}

/// The interpreter, comparing block checkpoints before every instruction
struct Shadow;

impl ExecutionBody<ReplayBus> for Shadow {
    const IDLE_LOOPS: bool = true;

    #[inline(always)]
    fn opcode(&mut self, _: &mut Sh2, _: &mut ReplayBus, _: u16) -> bool {
        false
    }

    fn block(&mut self, cpu: &mut Sh2, bus: &mut ReplayBus, ticks: u64) -> (u64, bool) {
        bus.checkpoint(cpu, ticks);
        (0, false)
    }
}

thread_local! {
    static SLICES: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
}

fn state_bytes(cpu: &Sh2) -> Vec<u8> {
    bincode::encode_to_vec(cpu, bincode::config::standard()).expect("Sh2 state encodes")
}

impl Sh2 {
    #[inline(never)]
    pub(super) fn execute_verified<Bus: BusInterface>(&mut self, ticks: u64, bus: &mut Bus) {
        let mut shadow = self.clone();
        let start_counter = bus.idle_cycle_counter();
        let limit = bus.idle_cycle_limit();

        let mut rec = RecBus::new(bus);
        self.jit.ensure_table(std::ptr::from_ref(RecBus::opcode_table()) as usize);
        self.execute_with_body(ticks, &mut rec, &mut Jit);
        let end_counter = bus.idle_cycle_counter();
        let log = rec.log.into_inner();

        let mut replay = ReplayBus {
            log,
            pos: std::cell::Cell::new(0),
            counter: start_counter,
            limit,
            error: RefCell::new(None),
            checks: 0,
        };
        shadow.execute_with_body(ticks, &mut replay, &mut Shadow);

        // Checkpoints after the final block of the slice
        let left = match replay.log.get(replay.pos.get()) {
            Some(Ev::Check { ticks_left, .. }) => Some(*ticks_left),
            _ => None,
        };
        if let Some(left) = left {
            replay.checkpoint(&shadow, left);
        }
        if replay.pos.get() != replay.log.len() {
            let rest = &replay.log[replay.pos.get()..];
            replay.fail(format!("block engine made {} more calls: {:x?}", rest.len(), &rest[..rest.len().min(4)]));
        }
        if replay.counter != end_counter {
            replay.fail(format!(
                "slice ended with counter {} (interpreter) vs {end_counter} (block engine)",
                replay.counter
            ));
        }
        if shadow.registers != self.registers {
            replay.fail(format!(
                "registers differ at slice end:\n  block engine {:x?}\n  interpreter  {:x?}",
                self.registers, shadow.registers
            ));
        }

        let slice = SLICES.with(|s| {
            let v = s.get() + 1;
            s.set(v);
            v
        });
        let deep_every = DEEP_EVERY.load(Ordering::Relaxed);
        let deep = deep_every != 0 && slice % deep_every == 0;
        if deep && replay.error.borrow().is_none() {
            let (a, b) = (state_bytes(self), state_bytes(&shadow));
            if a != b {
                let first = a.iter().zip(&b).position(|(x, y)| x != y);
                replay.fail(format!(
                    "serialized CPU state differs at slice end (byte {first:?} of {}/{})",
                    a.len(),
                    b.len()
                ));
            }
        }

        super::stat!(s => s.verified_slices += 1; s.verified_blocks += replay.checks; if deep { s.deep_compares += 1 });

        if let Some(error) = replay.error.into_inner() {
            panic!(
                "SH-2 block engine diverged from the interpreter ({}, slice {slice}, ticks {ticks}, start counter {start_counter}, limit {limit}):\n{error}",
                self.name
            );
        }

        let mut log = replay.log;
        log.clear();
        LOG_POOL.with_borrow_mut(|pool| pool.push(log));
    }
}

#[allow(dead_code)]
fn assert_table<B: BusInterface>(_: &OpcodeTable<B>) {}
