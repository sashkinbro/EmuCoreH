//! Bit-exact fast-forwarding of verified idle loops.
//!
//! 32X games spend a large share of SH-2 time spinning: `BRA` to itself, `DT` delay loops, and
//! loops that poll SDRAM, VDP status or the communication ports until another processor changes
//! a value. Interpreting every iteration is pure overhead when each iteration provably repeats the
//! previous one exactly.
//!
//! A loop head is armed when a backward branch targets it. On the next arrival at the head the
//! interpreter executes one iteration normally while recording everything the iteration did that
//! is not captured by the register file: cache hits (for their LRU effect), every bus access with
//! the cycle count at which it happened, and the cycle cost of the whole iteration. The iteration
//! is accepted when it ended at the head with the register file it started with (or, for two
//! counter-loop shapes, with only the loop counter decremented by one), did not change cache
//! contents, and every bus access is one the bus can replay exactly.
//!
//! From then on, an arrival at the head whose registers and cache epoch still match can skip whole
//! iterations. Nothing else runs while iterations are skipped, so every iteration reads exactly
//! what the first one reads: the recorded bus accesses are replayed once at their exact cycle
//! counts (side effect free reads are re-read and compared, SDRAM writes must store the value
//! memory already holds) and the number of iterations is then bounded by what keeps each of them
//! identical to an interpreted one. Communication port reads normally synchronize the other SH-2,
//! which would change state, so iterations stop before the first read that would do so. If a
//! replayed value differs, nothing is skipped and the interpreter continues from the head.
//! Iterations are only skipped while they complete before the cycle limit and within the current
//! instruction slice, so `execute` returns at exactly the same instruction boundary with exactly
//! the same state.

use std::sync::atomic::{AtomicU8, Ordering::Relaxed};

use crate::bus::{AccessContext, BusInterface, OpSize};
use crate::registers::Sh2Registers;
use crate::{EXTERNAL_ADDRESS_MASK, OpcodeTable, Sh2};

/// Watched loop head value that never equals a program counter (instructions are 2-byte aligned)
pub(crate) const NO_WATCH: u32 = u32::MAX;

const MAX_ITERATION_INSTRUCTIONS: u64 = 32;
const MAX_EVENTS: usize = 16;
const MAX_TOUCHES: usize = 48;
const MAX_OBSERVATION_ATTEMPTS: u8 = 3;
// A verified loop is only re-observed after this many consecutive arrivals with another register
// file (the first arrival after entering a loop usually differs from the steady state)
const MAX_ARRIVAL_MISMATCHES: u8 = 3;
// Failed attempts to prove the other SH-2 idle back off exponentially (in arrivals), up to this
const MAX_QUIESCENT_BACKOFF: u8 = 4;
// Iterations that change cache contents (line fills) are retried, they usually settle
const MAX_CACHE_CHANGED_ATTEMPTS: u8 = 12;
// Rejected loop heads are forgotten after this many observations so changing behavior is revisited
const FORGET_REJECTED_EVERY: u32 = 4096;
// An observation cut short by the end of the slice or the cycle limit raises the budget the next
// one needs; loops that cannot be observed within these caps are rejected
const MAX_OBSERVATION_BUDGET: u64 = 256;
// Rejected loop heads are remembered in a direct-mapped table (an eviction only costs one more
// observation)
const REJECTED_SLOTS: usize = 256;

/// Host-only counter that can be updated through a shared reference (the verified state is read
/// by `idle_skip` while its policy counters change)
#[derive(Debug, Default)]
struct Tally(AtomicU8);

impl Tally {
    fn new(value: u8) -> Self {
        Self(AtomicU8::new(value))
    }

    #[inline(always)]
    fn get(&self) -> u8 {
        self.0.load(Relaxed)
    }

    #[inline(always)]
    fn set(&self, value: u8) {
        self.0.store(value, Relaxed);
    }
}

impl Clone for Tally {
    fn clone(&self) -> Self {
        Self::new(self.get())
    }
}

/// Loop heads that were observed and found unskippable
#[derive(Debug, Clone, Copy)]
pub(crate) struct RejectedFilter(pub(crate) [u32; REJECTED_SLOTS]);

impl Default for RejectedFilter {
    fn default() -> Self {
        Self([NO_WATCH; REJECTED_SLOTS])
    }
}

impl RejectedFilter {
    #[inline(always)]
    pub(crate) fn slot(head: u32) -> usize {
        (((head >> 1) ^ (head >> 9)) as usize) & (REJECTED_SLOTS - 1)
    }

    #[inline(always)]
    fn contains(&self, head: u32) -> bool {
        self.0[Self::slot(head)] == head
    }

    fn insert(&mut self, head: u32) {
        self.0[Self::slot(head)] = head;
    }
}

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub(crate) struct IdleWatch {
    pub(crate) head: u32,
    /// Inline copy of `idle.cycles` when `idle.phase == Phase::Verified`, else `0` (lets
    /// `idle_cannot_skip` run without touching the boxed `IdleState` on every JIT slice entry).
    pub(crate) min_cycles: u32,
    pub(crate) min_insns: u8,
}

impl Default for IdleWatch {
    fn default() -> Self {
        Self { head: NO_WATCH, min_cycles: 0, min_insns: 0 }
    }
}

#[derive(Debug, Clone, Copy)]
enum Event {
    // Bus read (data or instruction fetch) issued with the cycle counter at `offset` cycles past
    // the start of the iteration
    Read { offset: u64, address: u32, size: u8, value: u32 },
    Write { address: u32, size: u8, value: u32 },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Counter {
    // Every register repeats
    None,
    // `DT Rn` loop of NOPs closed by BF or BF/S: continues while Rn - 1 != 0
    Dt(u8),
    // `TST Rn,Rn; BF/S head; ADD #-1,Rn`: continues while Rn != 0
    TstAdd(u8),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Phase {
    Armed,
    Verified,
}

#[derive(Debug, Clone)]
pub(crate) struct IdleState {
    // Loop head this state belongs to (`NO_WATCH` while the state is free)
    head: u32,
    phase: Phase,
    attempts: u8,
    // Instructions and cycles an observation must have left to have a chance of completing
    min_ticks: u64,
    min_budget: u64,
    observations: u32,
    mismatches: Tally,
    quiescent_failures: Tally,
    quiescent_backoff: Tally,
    // Recording state for the iteration under observation
    invalid: bool,
    start_counter: u64,
    events: Vec<Event>,
    touches: Vec<(u8, u8)>,
    opcodes: Vec<u16>,
    // Register file before each instruction of the observed iteration
    phases: Vec<Sh2Registers>,
    // Verified iteration
    pc_range: (u32, u32),
    registers: Sh2Registers,
    epoch: u32,
    cycles: u64,
    instructions: u64,
    last_ctx: (u32, u16),
    counter: Counter,
}

impl Default for IdleState {
    fn default() -> Self {
        Self {
            head: NO_WATCH,
            phase: Phase::Armed,
            attempts: 0,
            min_ticks: 0,
            min_budget: 0,
            observations: 0,
            mismatches: Tally::new(0),
            quiescent_failures: Tally::new(0),
            quiescent_backoff: Tally::new(0),
            invalid: false,
            start_counter: 0,
            events: Vec::new(),
            touches: Vec::new(),
            opcodes: Vec::new(),
            phases: Vec::new(),
            pc_range: (0, 0),
            registers: Sh2Registers::default(),
            epoch: 0,
            cycles: 0,
            instructions: 0,
            last_ctx: (0, 0),
            counter: Counter::None,
        }
    }
}

/// States of loop heads that are not the watched one, so that nested or alternating loops keep
/// what was verified about them
#[derive(Debug, Clone)]
pub(crate) struct IdleStash {
    slots: Vec<Box<IdleState>>,
    next_victim: usize,
}

const STASH_SLOTS: usize = 3;

impl Default for IdleStash {
    fn default() -> Self {
        Self {
            slots: (0..STASH_SLOTS).map(|_| Box::new(IdleState::default())).collect(),
            next_victim: 0,
        }
    }
}

fn op_size_len(size: u8) -> u32 {
    match size {
        OpSize::BYTE => 1,
        OpSize::WORD => 2,
        _ => 4,
    }
}

#[inline(always)]
fn registers_match(a: &Sh2Registers, b: &Sh2Registers, skip_gpr: Option<usize>) -> bool {
    if a.pc != b.pc
        || a.sr.t != b.sr.t
        || a.sr.s != b.sr.s
        || a.sr.interrupt_mask != b.sr.interrupt_mask
        || a.sr.q != b.sr.q
        || a.sr.m != b.sr.m
        || a.next_pc != b.next_pc
        || a.next_op_in_delay_slot != b.next_op_in_delay_slot
        || a.pr != b.pr
        || a.gbr != b.gbr
        || a.vbr != b.vbr
        || a.macl != b.macl
        || a.mach != b.mach
    {
        return false;
    }
    match skip_gpr {
        None => a.gpr == b.gpr,
        Some(n) => a.gpr[..n] == b.gpr[..n] && a.gpr[n + 1..] == b.gpr[n + 1..],
    }
}

impl Sh2 {
    /// Called by instructions that touch the bus without going through the recorded access paths;
    /// an iteration under observation containing one is never fast-forwarded.
    #[inline(always)]
    pub(crate) fn note_unrecorded_bus_access(&mut self) {
        if *self.idle_recording {
            self.idle.invalid = true;
        }
    }

    #[inline(always)]
    fn sync_idle_watch(&mut self) {
        let idle = &*self.idle;
        self.idle_watch.0 = if idle.phase == Phase::Verified && idle.cycles != 0 {
            IdleWatch {
                head: idle.head,
                min_cycles: idle.cycles.min(u64::from(u32::MAX)) as u32,
                min_insns: idle.instructions.min(255) as u8,
            }
        } else {
            IdleWatch { head: idle.head, min_cycles: 0, min_insns: 0 }
        };
    }

    /// Called by taken backward branches. Arms `target` unless it is already watched.
    #[inline(always)]
    pub(crate) fn note_backward_branch<Bus: BusInterface>(&mut self, target: u32) {
        if Bus::IDLE_LOOPS
            && target != self.idle_watch.0.head
            && !self.idle_rejected.contains(target)
        {
            self.idle_candidate(target);
        }
    }

    #[cold]
    #[inline(never)]
    fn idle_candidate(&mut self, target: u32) {
        if *self.idle_recording {
            return;
        }

        // Park the state of the previously watched head and bring in the one for `target`
        let stash = &mut *self.idle_stash;
        if let Some(i) = stash.slots.iter().position(|state| state.head == target) {
            std::mem::swap(&mut *self.idle, &mut stash.slots[i]);
        } else {
            let victim = match stash.slots.iter().position(|state| state.head == NO_WATCH) {
                Some(free) => free,
                None => {
                    let victim = stash.next_victim;
                    stash.next_victim = (victim + 1) % STASH_SLOTS;
                    victim
                }
            };
            std::mem::swap(&mut *self.idle, &mut stash.slots[victim]);

            let idle = &mut *self.idle;
            idle.head = target;
            idle.phase = Phase::Armed;
            idle.attempts = 0;
            idle.min_ticks = 0;
            idle.min_budget = 0;
            idle.mismatches.set(0);
            idle.quiescent_failures.set(0);
            idle.quiescent_backoff.set(0);
        }

        self.sync_idle_watch();
    }

    fn idle_reject(&mut self, head: u32) {
        self.idle_rejected.insert(head);
        self.idle.head = NO_WATCH;
        self.sync_idle_watch();
    }

    /// Whether a verified watched loop is guaranteed unable to skip any iteration in `(ticks, bus)`.
    #[inline(always)]
    pub(crate) fn idle_cannot_skip<Bus: BusInterface>(&self, bus: &Bus, ticks: u64) -> bool {
        let w = self.idle_watch.0;
        if w.min_cycles == 0 {
            return false;
        }
        let start = bus.idle_cycle_counter();
        let limit = bus.idle_cycle_limit();
        limit <= start
            || ticks < u64::from(w.min_insns)
            || (limit - 1 - start) < u64::from(w.min_cycles)
    }

    #[cold]
    #[inline(never)]
    pub(crate) fn idle_arrival_slow<Bus: BusInterface>(
        &mut self,
        bus: &mut Bus,
        opcode_table: &OpcodeTable<Bus>,
        ticks: u64,
    ) -> Option<u64> {
        let ticks = match self.idle.phase {
            Phase::Armed => {
                // Not enough slice or cycle budget left to observe a whole iteration
                let budget = bus.idle_cycle_limit().saturating_sub(bus.idle_cycle_counter());
                if ticks < self.idle.min_ticks || budget < self.idle.min_budget {
                    return Some(ticks);
                }
                self.idle_observe(bus, opcode_table, ticks)?
            }
            Phase::Verified => ticks,
        };

        if self.idle.phase != Phase::Verified || self.registers.pc != self.idle_watch.0.head {
            return Some(ticks);
        }

        Some(self.idle_skip(bus, ticks))
    }

    fn idle_observe<Bus: BusInterface>(
        &mut self,
        bus: &mut Bus,
        opcode_table: &OpcodeTable<Bus>,
        mut ticks: u64,
    ) -> Option<u64> {
        let head = self.registers.pc;
        self.idle.observations = self.idle.observations.wrapping_add(1);
        if self.idle.observations % FORGET_REJECTED_EVERY == 0 {
            *self.idle_rejected = RejectedFilter::default();
        }
        let start_registers = self.registers.clone();
        let start_counter = bus.idle_cycle_counter();
        let start_ticks = ticks;
        let start_epoch = self.cache.epoch();

        {
            let idle = &mut *self.idle;
            idle.invalid = false;
            idle.start_counter = start_counter;
            idle.events.clear();
            idle.touches.clear();
            idle.opcodes.clear();
            idle.phases.clear();
        }
        *self.idle_recording = true;

        let mut executed = 0;
        loop {
            let pc = self.registers.pc;
            let fetch_counter = bus.idle_cycle_counter();
            let fetch_hit = if pc >> 29 == 0 { self.cache.lookup(pc) } else { None };
            if let Some(touch) = fetch_hit {
                self.idle.touches.push(touch);
            } else if pc >> 29 > 1 {
                self.idle.invalid = true;
            }

            self.idle.phases.push(self.registers.clone());
            self.execute_single_instruction(bus, &mut crate::Interpreter, opcode_table);
            executed += 1;
            ticks -= 1;

            let AccessContext::Data { opcode, .. } = self.data_ctx else { unreachable!() };
            self.idle.opcodes.push(opcode);
            if fetch_hit.is_none() {
                self.idle.events.push(Event::Read {
                    offset: fetch_counter - start_counter,
                    address: pc & EXTERNAL_ADDRESS_MASK,
                    size: OpSize::WORD,
                    value: opcode.into(),
                });
            }

            if bus.should_stop_execution() || ticks == 0 {
                // Same exit as the interpreter loop; the observation resumes on a later arrival
                *self.idle_recording = false;
                self.idle_observation_incomplete(
                    head,
                    start_ticks - ticks,
                    bus.idle_cycle_counter() - start_counter,
                );
                return None;
            }

            if self.cache.epoch() != start_epoch {
                *self.idle_recording = false;
                self.idle.attempts += 1;
                if self.idle.attempts >= MAX_CACHE_CHANGED_ATTEMPTS {
                    self.idle_reject(head);
                }
                return Some(ticks);
            }

            if self.registers.pc == head && !self.registers.next_op_in_delay_slot {
                break;
            }

            if executed >= MAX_ITERATION_INSTRUCTIONS
                || self.idle.invalid
                || self.idle.events.len() > MAX_EVENTS
                || self.idle.touches.len() > MAX_TOUCHES
            {
                *self.idle_recording = false;
                self.idle_reject(head);
                return Some(ticks);
            }
        }
        *self.idle_recording = false;

        let accepted = !self.idle.invalid
            && self.idle.events.len() <= MAX_EVENTS
            && self.idle.touches.len() <= MAX_TOUCHES
            && self.idle.events.iter().all(|event| match *event {
                Event::Read { address, size, .. } => bus.idle_read_supported(address, size),
                Event::Write { address, size, .. } => bus.idle_write_supported(address, size),
            });

        let counter = if !accepted {
            None
        } else if registers_match(&self.registers, &start_registers, None) {
            Some(Counter::None)
        } else {
            self.idle_counter_shape(&start_registers)
        };

        match counter {
            Some(counter) => {
                let AccessContext::Data { pc, opcode } = self.data_ctx else { unreachable!() };
                let idle = &mut *self.idle;
                idle.phase = Phase::Verified;
                idle.registers = self.registers.clone();
                idle.epoch = start_epoch;
                idle.cycles = bus.idle_cycle_counter() - start_counter;
                idle.instructions = executed;
                idle.last_ctx = (pc, opcode);
                idle.counter = counter;
                idle.mismatches.set(0);
                idle.pc_range = idle.phases.iter().fold((u32::MAX, 0), |(low, high), phase| {
                    (low.min(phase.pc), high.max(phase.pc))
                });

                // A touch only writes fixed LRU bits of its entry, so a touch followed by an
                // identical one is overwritten completely and can be dropped
                let touches = &idle.touches;
                let kept: Vec<(u8, u8)> = touches
                    .iter()
                    .enumerate()
                    .filter(|&(i, touch)| !touches[i + 1..].contains(touch))
                    .map(|(_, &touch)| touch)
                    .collect();
                idle.touches = kept;
                self.sync_idle_watch();
            }
            None => {
                self.idle.attempts += 1;
                if !accepted || self.idle.attempts >= MAX_OBSERVATION_ATTEMPTS {
                    self.idle_reject(head);
                }
            }
        }

        Some(ticks)
    }

    fn idle_observation_incomplete(&mut self, head: u32, executed: u64, cycles: u64) {
        // The iteration needs more than what the cut-short observation had: ask for more next time
        let idle = &mut *self.idle;
        idle.min_ticks = idle.min_ticks.max(executed + 1);
        idle.min_budget = idle.min_budget.max(cycles + 1);
        if idle.min_ticks > MAX_ITERATION_INSTRUCTIONS || idle.min_budget > MAX_OBSERVATION_BUDGET
        {
            self.idle_reject(head);
        }
    }

    // Recognizes counter loops: exactly one register decremented by one, nothing else changed,
    // no bus accesses, and one of two fixed instruction shapes whose exit rule is known.
    fn idle_counter_shape(&self, start: &Sh2Registers) -> Option<Counter> {
        let idle = &*self.idle;
        if !idle.events.is_empty() || self.registers.sr.t || start.sr.t {
            return None;
        }

        let n = (0..16).find(|&i| self.registers.gpr[i] != start.gpr[i])?;
        if self.registers.gpr[n] != start.gpr[n].wrapping_sub(1)
            || !registers_match(&self.registers, start, Some(n))
        {
            return None;
        }

        let reg = n as u16;
        let ops = idle.opcodes.as_slice();
        let dt = 0x4010 | (reg << 8);
        let is_branch = |op: u16| op & 0xFF00 == 0x8B00 || op & 0xFF00 == 0x8F00;
        if ops.iter().filter(|&&op| op == dt).count() == 1
            && ops.iter().all(|&op| op == 0x0009 || op == dt || is_branch(op))
            && ops.iter().filter(|&&op| is_branch(op)).count() == 1
        {
            // T must come from DT: the branch has to follow the DT instruction
            let dt_idx = ops.iter().position(|&op| op == dt)?;
            let branch_idx = ops.iter().position(|&op| is_branch(op))?;
            if branch_idx > dt_idx {
                return Some(Counter::Dt(n as u8));
            }
        }

        let tst = 0x2008 | (reg << 8) | (reg << 4);
        let add = 0x70FF | (reg << 8);
        if ops.len() == 3 && ops[0] == tst && ops[1] & 0xFF00 == 0x8F00 && ops[2] == add {
            return Some(Counter::TstAdd(n as u8));
        }

        None
    }

    fn idle_skip<Bus: BusInterface>(&mut self, bus: &mut Bus, mut ticks: u64) -> u64 {
        let idle = &*self.idle;
        let skip_gpr = match idle.counter {
            Counter::None => None,
            Counter::Dt(n) | Counter::TstAdd(n) => Some(usize::from(n)),
        };

        // Nothing to skip when not even one whole iteration fits in the slice and cycle budget
        let start = bus.idle_cycle_counter();
        let limit = bus.idle_cycle_limit();
        if idle.cycles == 0
            || limit <= start
            || ticks < idle.instructions
            || (limit - 1 - start) < idle.cycles
        {
            return ticks;
        }

        if self.cache.epoch() != idle.epoch {
            self.idle.phase = Phase::Armed;
            self.idle.attempts = 0;
            self.sync_idle_watch();
            return ticks;
        }

        if !registers_match(&self.registers, &idle.registers, skip_gpr) {
            idle.mismatches.set(idle.mismatches.get() + 1);
            if idle.mismatches.get() >= MAX_ARRIVAL_MISMATCHES {
                idle.mismatches.set(0);
                self.idle.phase = Phase::Armed;
                self.idle.attempts = 0;
                self.sync_idle_watch();
            }
            return ticks;
        }
        idle.mismatches.set(0);

        let mut max_iterations = match idle.counter {
            Counter::None => u64::MAX,
            Counter::Dt(n) => self.registers.gpr[usize::from(n)].wrapping_sub(1).into(),
            Counter::TstAdd(n) => self.registers.gpr[usize::from(n)].into(),
        };

        // Nothing else runs while iterations are skipped, so every iteration reads exactly what
        // the first one reads. Bound the count by what keeps each skipped iteration identical to
        // an interpreted one: it must fit in the slice, finish before the cycle limit, and not
        // trigger other-CPU synchronization (which would change state)
        max_iterations = max_iterations
            .min(ticks / idle.instructions)
            .min((limit - 1 - start) / idle.cycles);

        // Reads that synchronize the other processor bound the iterations, unless that processor
        // is idle: then running it up to the last read in one go is the same as running it up to
        // each read in turn
        let unrestricted = max_iterations;
        let mut last_sync_read = None;
        for event in &idle.events {
            if let Event::Read { offset, address, size, .. } = *event {
                match bus.idle_read_deadline(address, size) {
                    Some(u64::MAX) => {}
                    None => {
                        max_iterations = 0;
                        last_sync_read = Some((address, size, offset));
                    }
                    Some(deadline) => {
                        let first = start + offset;
                        let allowed = if deadline < first {
                            0
                        } else {
                            ((deadline - first) / idle.cycles).saturating_add(1)
                        };
                        max_iterations = max_iterations.min(allowed);
                        last_sync_read = Some((address, size, offset));
                    }
                }
            }
        }

        let mut advance_other = false;
        if max_iterations < unrestricted {
            if idle.quiescent_backoff.get() != 0 {
                idle.quiescent_backoff.set(idle.quiescent_backoff.get() - 1);
            } else if bus.idle_other_quiescent() {
                idle.quiescent_failures.set(0);
                max_iterations = unrestricted;
                advance_other = true;
            } else {
                let failures = (idle.quiescent_failures.get() + 1).min(MAX_QUIESCENT_BACKOFF);
                idle.quiescent_failures.set(failures);
                idle.quiescent_backoff.set((1 << failures) - 1);
            }
        }

        let mut iterations = max_iterations;
        if iterations != 0 {
            for event in &idle.events {
                let ok = match *event {
                    Event::Read { offset, address, size, value } => {
                        bus.idle_replay_read(address, size, start + offset) == Some(value)
                    }
                    Event::Write { address, size, value } => {
                        bus.idle_write_is_noop(address, size, value)
                    }
                };
                if !ok {
                    iterations = 0;
                    break;
                }
            }
        }

        let counter = start + iterations * idle.cycles;
        ticks -= iterations * idle.instructions;

        if iterations != 0 && advance_other {
            if let Some((address, size, offset)) = last_sync_read {
                bus.idle_advance_other(address, size, start + (iterations - 1) * idle.cycles + offset);
            }
        }

        if iterations != 0 {
            // LRU updates compose into an idempotent function, so one application equals the
            // effect of any number of identical iterations
            for &(entry, way) in &idle.touches {
                self.cache.touch(entry, way);
            }

            let (pc, opcode) = idle.last_ctx;
            self.data_ctx = AccessContext::Data { pc, opcode };
            match idle.counter {
                Counter::None => {}
                Counter::Dt(n) | Counter::TstAdd(n) => {
                    let n = usize::from(n);
                    self.registers.gpr[n] = self.registers.gpr[n].wrapping_sub(iterations as u32);
                }
            }
            bus.increment_cycle_counter(counter - start);
        }

        ticks
    }

    /// Whether this processor, which is not executing, is inside its verified idle loop with
    /// nothing that could make it do anything but repeat the loop: whatever number of `execute`
    /// calls it is run for, it ends at the same instruction boundary in the same state.
    pub fn idle_quiescent<Bus: BusInterface>(&self, bus: &mut Bus, counter: u64) -> bool {
        if self.reset_pending || bus.reset() || self.dmac.any_channel_pending() {
            return false;
        }

        // No exception that `execute` samples at the start of a slice could be taken
        let external_level = bus.interrupt_level();
        let internal_level = self.sh7604.internal_interrupt.priority;
        let mask = self.registers.sr.interrupt_mask;
        if (external_level > mask && external_level >= internal_level) || internal_level > mask {
            return false;
        }

        let epoch = self.cache.epoch();
        std::iter::once(&**self.idle).chain(self.idle_stash.slots.iter().map(|state| &**state)).any(
            |idle| {
                idle.head != NO_WATCH
                    && idle.phase == Phase::Verified
                    && (idle.pc_range.0..=idle.pc_range.1).contains(&self.registers.pc)
                    && idle.counter == Counter::None
                    && idle.epoch == epoch
                    && idle.phases.iter().any(|phase| {
                        phase.pc == self.registers.pc
                            && registers_match(&self.registers, phase, None)
                    })
                    // The loop must still read what it read and write what memory holds
                    && idle.events.iter().all(|event| match *event {
                        Event::Read { offset, address, size, value } => {
                            bus.idle_replay_read(address, size, counter + offset) == Some(value)
                        }
                        Event::Write { address, size, value } => {
                            bus.idle_write_is_noop(address, size, value)
                        }
                    })
            },
        )
    }

    /// Records a data read made while an iteration is under observation.
    #[cold]
    #[inline(never)]
    pub(crate) fn idle_record_read<const SIZE: u8, Bus: BusInterface>(
        &mut self,
        address: u32,
        bus: &mut Bus,
    ) -> u32 {
        let region = address >> 29;
        let counter = bus.idle_cycle_counter();
        let hit = if region == 0 { self.cache.lookup(address) } else { None };

        *self.idle_recording = false;
        let value = match SIZE {
            OpSize::BYTE => self.read_byte(address, bus).into(),
            OpSize::WORD => self.read_word(address, bus).into(),
            _ => self.read_longword(address, bus),
        };
        *self.idle_recording = true;

        let idle = &mut *self.idle;
        if region > 1 {
            idle.invalid = true;
        } else if let Some(touch) = hit {
            idle.touches.push(touch);
        } else if address & (op_size_len(SIZE) - 1) != 0 {
            idle.invalid = true;
        } else {
            idle.events.push(Event::Read {
                offset: counter - idle.start_counter,
                address: address & EXTERNAL_ADDRESS_MASK,
                size: SIZE,
                value,
            });
        }

        value
    }

    /// Records a data write made while an iteration is under observation.
    #[cold]
    #[inline(never)]
    pub(crate) fn idle_record_write<const SIZE: u8, Bus: BusInterface>(
        &mut self,
        address: u32,
        value: u32,
        bus: &mut Bus,
    ) {
        let region = address >> 29;
        let hit = if region == 0 { self.cache.lookup(address) } else { None };

        *self.idle_recording = false;
        match SIZE {
            OpSize::BYTE => self.write_byte(address, value as u8, bus),
            OpSize::WORD => self.write_word(address, value as u16, bus),
            _ => self.write_longword(address, value, bus),
        }
        *self.idle_recording = true;

        let idle = &mut *self.idle;
        if region > 1 || address & (op_size_len(SIZE) - 1) != 0 {
            idle.invalid = true;
            return;
        }

        if let Some(touch) = hit {
            idle.touches.push(touch);
        }
        idle.events.push(Event::Write {
            address: address & EXTERNAL_ADDRESS_MASK,
            size: SIZE,
            value: value & OpSize::mask::<SIZE>(),
        });
    }
}
