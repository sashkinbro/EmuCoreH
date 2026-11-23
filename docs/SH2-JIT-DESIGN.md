# SH-2 block JIT for the 32X: design

Status: phase (a) implemented (`core-jgenesis/cpu/sh2-emu/src/predecode.rs`), phases (b)-(d) planned.
Goal: a block-compiling execution backend that is **bit-exact** with the interpreter in
`cpu/sh2-emu/src/lib.rs` (`execute_with_body`): same register file, same cache state (including the
pseudo-LRU bits), same bus accesses at the same cycle counts, same `execute()` return points, same
savestate bytes. The oracle (24 jgenesis suites, state hashes) is the acceptance test; the host unit test
`predecoded_blocks_match_interpreter_exactly` is the fast inner loop.

The existing `native*` "JIT" (feature `jit-runtime`) is a guarded trace that calls back into Rust
`prepare()`/`complete()` for every guest instruction. It is 2-2.6x slower than the interpreter and is
reference only; nothing here builds on its per-instruction callback design.

## 1. The reference semantics we must reproduce

Per `execute(ticks)` slice (the 32X uses `SH2_EXECUTION_SLICE_LEN = 50`):

1. reset handling, DMA ticks, a pending delay slot is executed, then interrupts are sampled **once**;
2. loop: `body.block()` hook, idle-loop arrival check (`pc == idle_watch`), then one instruction:
   * fetch `read_opcode(pc)`: for `pc >> 29 == 0` a cache hit costs nothing but updates the pseudo-LRU
     bits of the entry (and the host `fetch_memo`); a miss refills the line through the bus; other regions
     go to the bus with `AccessContext::Fetch`;
   * `pc = next_pc; next_pc = pc + 2; next_op_in_delay_slot = false; data_ctx = Data{pc, opcode}`;
   * the handler; `bus.increment_cycle_counter(1)`; `if bus.should_stop_execution() { return }`.

`should_stop_execution` is `cycle_counter >= cycle_limit`; the limit is derived from the *other* CPU and
the counter is advanced by every bus access, so a stop can become true after any instruction. Everything
below keeps this per-instruction check, which is what makes block formation trivially safe: a block never
needs to prove "no stop can occur inside me", it simply tests after each instruction like the interpreter.
(Later phases that batch cycle charges must replace that by the entry guard in section 6.)

Guest-visible state beyond the register file is the CPU cache (tags, valid bits, LRU bits, RAM), the
`data_ctx` field (it is serialized!), the idle-loop state and the bus. Host-only memos (`fetch_memo`,
`epoch`, idle recording) are `HostCache` values that are never serialized and default to "empty".

## 2. Block formation

A block is formed at a pc from **cache-resident code only**, with no side effects:

* `pc >> 29 == 0`, even, and `CpuCache::peek_fetch(addr)` hits (side-effect-free twin of the fetch path;
  same way selection as the real lookup: highest way first). The block records, per 16-byte line it spans,
  `(line address, way, entry, generation)`; per instruction `(pc, opcode, handler, line index, touches-cache)`.
* Uncached code (`0x2xxxxxxx`, cache disabled) and cache misses never form blocks: the interpreter runs
  them (and its miss path performs the refill, after which the next arrival forms the block). Uncached
  fetches cost a bus access per instruction with cycle charges and possible synchronization, so a block
  could not fold them anyway; they are the first extension candidate (section 5, uncached ROM/SDRAM).
* A block ends after an unconditional delayed branch **plus its delay slot** (`BRA BSR BRAF BSRF JMP JSR RTS
  RTE`, and also `BT/S BF/S`), after `TRAPA`/`SLEEP`, at 16 instructions (a trailing delay slot may add one),
  at a 4th cache line, or where the next fetch would miss. `BT`/`BF` do not end a block: they are side
  exits (see control-flow guard below), so a block can extend through not-taken conditional branches, the
  common case in loop bodies.
* No instruction is rejected at formation: unknown or illegal opcodes get the interpreter's own handler
  (`OpcodeTable::decode`), which raises the exception exactly as the interpreter does.

Direct-mapped table of 2048 blocks per CPU (hash of pc), inline arrays, allocated on first use. It is a
`HostCache` field of `Sh2` (clone gives an empty cache, savestates ignore it, state load starts empty).

## 3. Validity: fetch-side cache effects

Blocks may only run while a real fetch would return the identical words from the identical ways. The CPU
cache carries host-only change counters (`cache::LineGens`):

* one counter per `(way, entry)`, bumped on line refill, write-through that changes a word, data-array
  writes, address-array writes and associative purges (all four ways of the entry for the last two, since
  they can create duplicate tags and change which way wins);
* one global `conf` counter bumped by CCR writes, `purge_all`, the debugger view and `native_view`.

LRU-only updates never bump anything, so ordinary hits (code or data) do not invalidate blocks, while a data
miss that evicts one of the block's lines (or a store into it) does. A block is valid iff `conf` and every
recorded line counter match. It is checked at block entry and again after **every instruction that can touch
the cache** (anything not on the register-only allow-list in `is_register_only`), because a store or a
refill inside the block may change the very next instruction. An invalid block is abandoned at that
boundary and the interpreter executes the next instruction normally.

Instruction fetch hits have exactly two side effects, and both are re-applied precisely:

* LRU update: the interpreter performs it when the host `fetch_memo` does not cover the line (first fetch
  from a line, or any data access to the same cache entry invalidated the memo). The block compares the
  memo against the instruction's line and calls `CpuCache::fetch_hit(way, entry, line)` (LRU update + memo
  set, the same code the real slow path runs) under the same condition. Fetches that the interpreter
  satisfies from the memo cost the block one load and compare.
* No cycles: a cache hit fetch charges nothing.

## 4. Control flow, delay slots, interrupts, slices

* **Control-flow guard.** Before each instruction the block checks `registers.pc == recorded pc`. Taken
  `BT`/`BF`, exceptions (`TRAPA`, illegal opcode, `SLEEP`'s pc rewind) and anything unforeseen simply leave
  the block; correctness never depends on the formation-time classification of branches, only
  block *usefulness* does.
* **Delay slots** execute as the next instruction of the block, as in the interpreter (the branch handler
  sets `next_pc`/`next_op_in_delay_slot`; the slot is fetched at `branch_pc + 2`). If `ticks` runs out or a
  stop is raised between a branch and its slot, the pending slot is handled by the next `execute()` slice
  exactly as before (it runs before interrupts are sampled).
* **Slice length and interrupts.** A block executes `min(block length, ticks)` instructions; interrupts
  are sampled only at slice starts in both backends, DMA/FRT/WDT are ticked outside the instruction loop in
  both, so nothing about blocks changes their timing. `execute()` returns at exactly the same instruction
  boundary because the stop check is per instruction.
* **Idle-loop skipping** (`idle.rs`) keeps working unchanged: a block never runs an instruction whose pc is
  the watched loop head (the `pc == idle_watch` test is part of the per-instruction guard), the main loop
  then performs the arrival logic and single-steps through the interpreter, including observation, which
  always uses interpreter paths with `idle_recording` set.
* Only buses with `IDLE_LOOPS = true` (the real 32X bus; `debug` buses are excluded by that contract) run
  blocks; `check_execute` is still called per instruction.

## 5. Memory accesses (phase c)

Phase (a)/(b) leave every load/store to the existing Rust paths (`read_*`/`write_*`), so memory side
effects are unchanged by construction. Phase (c) adds inlined fast paths, **only** where the Rust path's
effects can be reproduced mechanically:

* cached data hit (`cache.read_u8/u16/u32`): way search + LRU update + the memo invalidation of the entry,
  all straight-line code with the same order of effects (a hit never charges cycles);
* cached miss, uncached (`0x2...`) SDRAM, ROM, VDP/MMIO, communication ports, other-CPU synchronization:
  call out to Rust (`extern "C"` helper receiving `&mut Sh2`, `&mut Bus`); the helper may end the block
  (cycle limit reached, cache gen changed) by returning a flag the block epilogue tests;
* SDRAM has a constant charge (`SH2_SDRAM_READ_CYCLES`) and no side effects, so uncached SDRAM word/long
  fetch and data reads can be inlined once the charge is folded exactly (see section 6);
* stores additionally bump the line counter of the hit line (mirrors `write_through_*`) and must be
  tracked for **self-modifying code in uncached memory**: blocks over uncached SDRAM/ROM (a later
  extension) need a per-page write counter on the 32X bus (a store into a page holding block code bumps a
  host counter the block validates), because their words are not protected by the cache counters.

## 6. Cycle accounting and register allocation (phase b/c)

Phase (a) charges one cycle per instruction and tests the stop condition after each, like the interpreter.
Compiled code may batch this, but only under an entry guard that makes it equivalent:

* block entry condition `cycle_counter + worst_case_cycles(block) < cycle_limit` and `ticks >= n`, where
  worst case counts every instruction's base cost plus the maximum charge of each bus call a block can
  make *without* leaving; otherwise run the block in the checked (phase a) form or interpret. Inside the
  guard no stop can fire mid-block, so one add at exit replaces per-instruction increments, **provided the
  bus calls inside see the true counter** (the 32X bus uses it to order synchronization with the other
  SH-2): the counter is stored before every call-out and reloaded after.
* helpers that may run the other CPU (communication port reads, bus synchronization) are always calls with
  a materialized counter; the entry guard is recomputed after them or the block exits.

ARM64 register allocation (phase b): the 16 guest GPRs live in memory (`Sh2Registers.gpr`, host
`x19 = &Sh2`); within a block a straight-line cache of the guest registers in host registers (x0-x15 temps,
guest r0-r15 mapped to callee-saved x20-x28/x0-x7 by first-use) with write-back at every call-out and at
every exit, including the side exits of `BT`/`BF`. The T bit lives in a host register (`w9`) in blocks and is
spilled to `sr.t` on call-outs/exits. `pc`, `next_pc`, `next_op_in_delay_slot` and `data_ctx` are
materialized only at call-outs/exits (they are observable only by handlers, the bus and savestates; the
block exit path always writes the exact values the interpreter would hold after the last executed
instruction).

## 7. W^X, icache, Android arm64 (phase b)

Code buffers are `mmap`ed RW, filled, then `mprotect`ed RX (never RWX; SELinux on stock Android denies
`execmem` for app domains on many devices, but anonymous RW->RX remapping works for untrusted_app) and
flushed with `__builtin___clear_cache` / `libc::cacheflush` on the written range before the first
execution. Blocks live in one append-only arena per CPU with a linear allocation pointer; the arena is
reset wholesale (drop to a fresh mapping) when full or on any global invalidation (`conf` bump, savestate
load, reset), which also removes the need for per-block unlink bookkeeping: the block table is simply
cleared. Generated code never embeds addresses of `Sh2`/`Bus` objects: both are passed in registers on
entry, so state load/clone cannot leave dangling constants. No generated code or compiled-block table is
ever serialized; a loaded state starts with an empty arena.

## 8. Savestates and host state

All JIT state is `HostCache`: not encoded, defaulted on decode, emptied on `Clone`. Loading a state yields a
fresh `Sh2` with an empty block table and zero line counters; the first arrival at any pc re-forms its
block from the loaded cache contents. `data_ctx` is part of the serialized state, so blocks (all phases)
must leave it holding `Data{pc, opcode}` of the last executed instruction at every exit.

## 9. Phases and exit criteria

* **(a)** predecoded block cache + threaded handler calls. Done: `predecode.rs`, runtime-toggled by the
  hidden core option `jgenesis_sh2_predecode=enabled` (`sh2_emu::set_predecode_enabled`), default OFF.
  Verified by the differential fuzz test and the oracle.
* **(b)** ARM64 codegen for register-only ALU/move/branch instructions with an interpreter fallback per
  instruction (call-out to the same handler, state spilled). Per-instruction stop checks stay until (c).
* **(c)** inline cached-memory fast paths, folded cycle charges under the entry guard, uncached SDRAM/ROM
  blocks with write tracking.
* **(d)** 68000: same skeleton (host `HostCache` block table, validation counters on the code regions the
  Genesis bus exposes, per-instruction exactness with prefetch-queue/cycle accounting), plus its own
  exception and `STOP` handling; its bus is not cached so validation is write tracking on ROM/RAM pages.

Each step is accepted only when the 24 oracle suites match bit-exactly with the feature ON and the 32X suites
are faster in paired runs.
