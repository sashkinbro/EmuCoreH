# SH-2 block JIT for the 32X: design

Status: phase (a) implemented (`core-jgenesis/cpu/sh2-emu/src/predecode.rs`); JIT front half
implemented (`core-jgenesis/cpu/sh2-emu/src/jit/`: block discovery with static cycle sums, block cache
with code-line invalidation and chaining, threaded micro-op backend, lockstep differential verifier on
real ROMs, AArch64 encoder and first translator), see sections 11-15; ARM64 code generation (phase b) next.
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
  instruction (call-out to the same handler, state spilled). The block engine of section 11 already provides
  the static segment costs and guards, so per-instruction stop checks are not needed even in (b). Front half
  done (sections 11-14), emitter interface in section 13, remaining work in section 15.
* **(c)** inline cached-memory fast paths, folded cycle charges under the entry guard, uncached SDRAM/ROM
  blocks with write tracking.
* **(d)** 68000: same skeleton (host `HostCache` block table, validation counters on the code regions the
  Genesis bus exposes, per-instruction exactness with prefetch-queue/cycle accounting), plus its own
  exception and `STOP` handling; its bus is not cached so validation is write tracking on ROM/RAM pages.

Each step is accepted only when the 24 oracle suites match bit-exactly with the feature ON and the 32X suites
are faster in paired runs.

## 10. Measurements that shape phases (b)-(d)

Collected on the Lenovo with the phase (a) executor (32X, 1800 frames per suite):

* **Slices are tiny.** `execute_cpus` leapfrogs the two SH-2s against the global cycle budget, so an
  `execute(50)` call ends on `should_stop_execution` after only ~6 instructions on average (x_mk2: 118 M
  stop returns, 738 M instructions, 265 M block entries, i.e. 2.8 instructions per block entry; 69 M block
  exits are taken branches). Anything with a per-entry cost (block lookup, validation, register
  load/store in generated code) pays that cost every ~3 instructions. Codegen must therefore make entry and
  exit nearly free (validation by a single global counter, no register-file marshalling on the fast path)
  or it cannot win.
* **No uncached code.** All executed code was cache-resident (`pc >> 29 == 0`); formation misses are
  0.15 % of lookups. Uncached-code blocks are not needed for the 32X titles tested.
* **Instruction mix is dominated by spin loops, not computation** (share of executed instructions, mean of
  x_mk2/doom/x_spiderman): nop 12.8 %, bra 10.0 %, bt 9.9 %, cmp/eq 5.2 %, tst #imm 4.8 %,
  mov.l @Rm,Rn 4.4 %, mov.w @(d,GBR),R0 3.9 %, swap.b 3.7 %, add #imm 2.9 %, bf/s 2.9 %, tst 2.7 %, bf 2.5 %.
  Roughly 45-50 % of executed instructions are poll/delay loops (`bra`-to-self + `nop`, `mov.w @(d,GBR),R0;
  tst #imm,R0; bt`, `cmp/eq`+`bt`). Compiling them removes interpreter overhead but not their cycle
  charges; extending bit-exact idle-loop fast-forwarding (`idle.rs`) to the polling shapes it still rejects
  is worth more than faster dispatch of ALU instructions.
* **Phase (a) result.** Bit-exact (fuzz test plus all oracle suites), speed within noise of the
  interpreter (paired OFF/ON medians 0.96-1.04 per suite, mean about 0.99): the interpreter's fetch is
  already memoized and its dispatch is a direct table call, so predecoding alone removes too little per
  instruction to pay for the per-entry cost above. It therefore stays default OFF.

## 11. Block engine (implemented: `cpu/sh2-emu/src/jit/`)

Selected by `sh2_emu::set_jit_enabled` / `s32x_core::set_sh2_jit`, i.e. the hidden core option
`jgenesis_sh2_jit=enabled` (default OFF; takes precedence over `jgenesis_sh2_predecode`). Only buses with
`IDLE_LOOPS = true` use it. Hook: one branch in `Sh2::execute` (`execute_jit`) and one
`HostCache<JitCache>` field in `Sh2`; everything else is in the `jit` module, the code-line bitmap in
`cache.rs`, and one `jit-verify`-only default method on `BusInterface` (`jit_checkpoint`).

**Formation (`jit/form.rs`).** Side-effect free, from two regions:

* cached space (`pc >> 29 == 0`): words that hit the cache (`CpuCache::peek_fetch`, same way selection as a
  real fetch);
* the cache data array used as on-chip RAM (`pc >> 29 == 6`): Virtua Racing Deluxe enters the engine there
  25 M times in 900 frames. These fetches have no side effects at all (no LRU, no memo, no cycles).

A block ends after a delayed branch plus its slot (`BRA BSR BRAF BSRF JMP JSR RTS RTE BT/S BF/S`), after
`TRAPA`/`SLEEP`, at 32 instructions (+ a trailing slot), at 6 cache lines, at a region change, or where the
next fetch would miss. `BT`/`BF` are side exits. Uncached code (`0x2xxxxxxx`) is never formed: none of the
seven 32X suites executes any (measured: zero engine entries from that region), so SDRAM/ROM page tracking
on the 32X bus is not needed now and the bus is untouched (section 5 still describes how to add it).

**Micro-ops.** One per instruction (`Uop`, 32 bytes):

* `K_NATIVE`: register-only instruction, run by the interpreter's own register method (inlined into a
  monomorphic function) without any pc/`data_ctx`/cycle/stop bookkeeping. `MOVA` is native with its result
  computed at formation time (outside delay slots).
* `K_BRANCH` (`BT`/`BF`): target resolved at formation time; ends its segment; leaves the block when taken.
* `K_DELAYED`: `BRA BSR BRAF BSRF JMP JSR RTS BT/S BF/S`, targets static where possible; writes `next_pc`
  and the delay flag exactly as the interpreter's handler would; idle-loop arming (`note_backward_branch`)
  decided statically.
* `K_CALLOUT`: everything else (memory, `RTE`, `TRAPA`, `SLEEP`, illegal opcodes, any instruction in a delay
  slot that reads the pc): the interpreter's handler with exactly materialized state (`pc`, `next_pc`, delay
  flag, `data_ctx`). The registers are authoritative after a call-out; the block continues only if `pc`
  equals the recorded successor.

The 15 hottest native instructions and 6 hottest call-outs are dispatched through a `match` on a hot code
(direct calls) instead of the stored function pointer, as the interpreter does for its hottest opcodes.

**Static cycle sums and the stop check.** Consecutive non-call-out micro-ops form a *segment* (a `BT`/`BF`
ends one). Costs are the interpreter's: 1 per instruction, +1 for `BRA BSR BRAF BSRF JMP JSR RTS`, +2 for a
taken `BT`/`BF`, +1 for a taken `BT/S`/`BF/S`. Each micro-op stores `guard` = the worst-case cost of its
segment from it up to, excluding, the segment's last instruction. The entry condition
`counter + guard < limit` proves that `should_stop_execution` cannot become true before the last
instruction, so the whole segment runs without per-instruction accounting and its cost is charged once at
its end (`increment_cycle_counter(spent)`, then one stop check). Otherwise the segment runs in checked mode
(local compare after every instruction, exact interpreter stop point). Call-outs charge pending cycles
before the handler runs, so every bus access sees the true counter. A block executes
`min(len, ticks left, distance to the watched idle-loop head)` instructions; blocks are at most 33
instructions, shorter than the 50-instruction slice, so the slice budget never needs splitting inside a
segment's guard.

**Exit materialization.** Native micro-ops never touch `pc`; at an exit after native op `i` at address `A`:
normal op: `pc = A+2, next_pc = A+4, delay = false`; delayed branch: `pc = A+2` (`next_pc`/delay already set
by the branch); delay slot: `pc = next_pc, next_pc = pc+2, delay = false`; taken `BT`/`BF`: set by the
branch. `data_ctx = Data{A, opcode}` at every exit (it is serialized).

**Fetch side effects.** For cached-space blocks the fetch memo / LRU update (`CpuCache::fetch_hit`) is
applied before the first instruction, at every line change and after every call-out (a data access to the
same entry invalidates the memo), exactly when the interpreter's fetch would apply it.

**Invalidation: code-line bitmap.** `LineGens` (host-only, in `cache.rs`) gains a bitmap with one bit per
cache storage line (4 ways x 64 entries, which is also the 4 KB data array) and a `code` counter. Forming a
block marks its lines; any change to a marked line (refill, write-through that changes a word, data-array
write, address-array write, associative purge) clears the bit and bumps `code`; CCR changes bump it too.
A block is valid iff its stamp equals `code` (one compare, the common case) or, after `code` moved, its own
lines' counters are unchanged (cold path, re-stamps). Unlike the predecoded executor's `any` counter, `code`
does not move when unrelated data lines are refilled. Validation runs at block entry and after every
call-out that can touch the cache.

**Refresh instead of re-forming.** Code lines are evicted and refilled all the time (4 KB cache), which
invalidates blocks whose code comes back unchanged. When a block's counters moved, `Block::refresh`
re-locates each of its lines (same way selection as a fetch), compares the recorded instruction words with
cache RAM and, if all are identical, adopts the lines' current ways and counters (everything else in a block
depends only on its addresses and words). Only a changed word forces a re-formation. Spider-Man: 217 k
refreshes vs 4.6 k formations in 600 frames.

**Block cache and chaining.** 8192 direct-mapped slots per CPU (`HostCache`, cleared on clone and state
load); a slot is a ~140-byte header with the micro-ops in a right-sized boxed slice. With 2048 slots
Motocross Championship thrashed (267 k formations in 600 frames, mostly slot conflicts; 19 k with 8192). Each block has two links (fall-through/static target, taken/dynamic target) storing
`(pc, slot, version)`; every overwrite of a slot bumps its version, so links to replaced or re-formed blocks
are unlinked lazily without bookkeeping. `jit_run` follows links between blocks inside one `execute()` call
(re-checking the idle watch, delay flag and tick budget at every transition).

## 12. Differential verification (`jit/verify.rs`, `cpu/sh2-difftest`)

Feature `jit-verify` (host only). With `set_jit_verify(true)` every `execute()` slice runs twice:

1. the block engine on a `RecBus` wrapping the real bus (a concrete type holding a vtable of the real bus's
   monomorphized methods, so it has its own opcode table): every read, cache-line read, write and query
   (`reset`, `interrupt_level`, `dma_request_*`, serial, all `idle_*`) is forwarded and logged with address,
   size, access context, value and the cycle counter before and after; after every executed block the
   engine logs a checkpoint (instructions left in the slice, full `Sh2Registers`, counter, `data_ctx`);
2. a clone of the CPU taken before step 1 (host idle-loop state included, so both sides make identical
   idle-loop decisions) runs the plain interpreter on a `ReplayBus` that answers from the log and records
   the first call that differs in kind, address, size, value, context or counter. Its per-instruction hook
   compares the register file, counter and `data_ctx` at every block checkpoint (the main loop's tick count
   identifies the boundary).

At slice end the counters, register files and log consumption must match, and every 64th slice the complete
serialized CPU states (cache RAM, tags, LRU bits, peripherals) are compared byte for byte. Any difference
panics with a description. Nested executions (communication-port synchronization runs the other SH-2 inside
a read) are verified recursively with their own logs; the replay returns the recorded read value.

`cpu/sh2-difftest` (workspace member, host binary) runs a 32X ROM headless with the BIOS from
`E:/Sega/bios DreamCust`: `--mode verify` (lockstep), `interp`/`predecode`/`jit` (speed plus a hash chain of
the full serialized emulator state after every frame: equal chains = bit-exact runs), `compare` (two
emulators, full state compared every frame). The default `verify` feature also counts engine statistics;
build with `--no-default-features` for speed numbers. Owner ROMs are read from their paths, never copied.

Unit tests: `predecode::tests::jit_blocks_match_interpreter_exactly` (random programs in cached space,
uncached space and the data array; random registers/T/IRQs/limits/tick counts; stores into the data array
modify running code; compares serialized state after every slice, memory, the cycle-stamped bus log and the
counter), `jit::a64::tests` (encoder, section 13).

## 13. ARM64 emitter interface (phase b design)

The compiler consumes the formed `Block` (micro-ops with kinds, static targets, costs, guards, segment
boundaries, line references) and produces one AArch64 function per block. The threaded executor stays the
reference and the deoptimization target: any condition the compiled code does not handle exits to it at an
instruction boundary with exact interpreter state, so compiled code only ever covers the fast path.

**Encoder.** `jit/a64.rs` (feature `jit-arm64`, always built for tests): hand-written, no dependencies,
labels with fixups for `B/BL` (imm26), `B.cond/CBZ/CBNZ` (imm19), `TBZ/TBNZ` (imm14); moves
(`MOVZ/MOVK/MOVN`, `mov_imm32` picking the shortest form incl. logical immediates), add/sub (imm, shifted
reg, with flags, carry), logical (reg and bitmask immediates with a complete encoder), bitfield ops
(`UBFM/SBFM/BFM`, shifts, extends, `EXTR`-rotate, `REV16`), `CSEL/CSINC/CSET`, `MADD/MUL/SMULL/UMULL`,
loads/stores (unsigned offset, register offset, `LDP/STP` signed/pre/post), `BR/BLR/RET`. Every encoding is
checked against the NDK's LLVM assembler (`clang --target=aarch64-linux-android` + `llvm-objdump`) in
`jit::a64::tests`, and the bitmask-immediate encoder is checked by decode round trip over every encodable
32-bit pattern. dynasm-rs was not used: the needed subset is small, and a dependency-free encoder keeps the
Android build and host tests simple.

**Translator (started).** `jit/a64_translate.rs` emits code for all register-only instructions the block
engine runs natively except the multiply/divide family (`MOV ADD SUB AND OR XOR TST CMP/EQ/HS/GE/HI/GT
CMP/PZ/PL EXTU/EXTS NOT NEG SWAP.B/W XTRCT DT SHLL/SHAL/SHLR/SHAR/ROTL/ROTR SHLLn/SHLRn MOVT CLRT SETT NOP`
and the immediate forms `MOV ADD CMP/EQ TST AND OR XOR #imm`), with the final instruction selection
(`CSET` for `T`, `REV16`+`BFXIL` for `SWAP.B`, `EXTR` for `XTRCT`/`SWAP.W`, `SUBS` for `DT`, bitmask
immediates where encodable). In this first form guest registers and `T` are memory operands through
`x19 = &mut Sh2Registers` (offsets from `offset_of!`). `jit/a64_sim.rs` is a test-only AArch64 subset
simulator that executes the generated code on the x86 host against a real `Sh2Registers`;
`translated_register_ops_match_interpreter` runs 47 instruction patterns x 400 random operand/register/`T`
cases through both the simulator and the interpreter's handlers and requires identical register files.

**Entry ABI.** `extern "C-unwind" fn(frame: *mut JitFrame) -> u32` where `JitFrame` (`#[repr(C)]`, on the
Rust stack of `jit_run`) holds `cpu: *mut Sh2`, `bus: *mut ()`, `helpers: *const Helpers` (monomorphized
`extern "C-unwind"` trampolines for the current bus type: call-out by micro-op index, cache-line refill,
validation), `counter: *mut u64`, `limit: *const u64` (from `BusInterface::native_bus_view`, which the 32X
bus already implements with exactly these semantics), `ticks_left`, `idle_watch`, and an exit record
(`executed`, `exit kind`, `link index`). Generated code never embeds `Sh2`/bus addresses, so state load,
clone and the rewind/run-ahead copies cannot leave dangling constants. Field offsets come from
`std::mem::offset_of!` on the Rust structs (`Sh2.registers.gpr`, `sr.t`, `gbr`, `pr`, ...), so no struct
needs to change layout; they are compile-time constants of the emulator build.

**Register allocation (AAPCS64).**

| host | use |
|------|-----|
| `x19` | `*mut Sh2` (guest register file at `offset_of!(Sh2, registers)`) |
| `x20` | `*mut JitFrame` |
| `x21` | cycle counter value (loaded at entry, stored before every call-out and exit, reloaded after call-outs) |
| `x22` | cycle limit (reloaded after call-outs; the other SH-2 never changes it, but call-outs are opaque) |
| `x23` | instructions left in the slice |
| `w24` | guest `T` bit (0/1), spilled to `sr.t` at call-outs/exits; compare+`BT/BF` pairs branch on NZCV directly |
| `x25`-`x28` | per-block cache of the 4 most-used guest registers (e.g. `R0` of GBR polls, loop counters) |
| `x0`-`x15` | temporaries and per-segment guest registers (allocated by first use, written back at segment end) |
| `x16`/`x17` | scratch for link-table jumps; `x18` is never touched (Android shadow call stack) |
| `x29`/`x30` | frame pointer / link register |

Guest registers live in `Sh2Registers.gpr`. Inside a segment (no calls) they are cached in host registers;
dirty ones are written back before every call-out (interpreter handlers read the register file from memory)
and at every exit including `BT`/`BF` side exits, and the callee-saved cache is reloaded after call-outs.
`SR` (except `T`), `GBR`, `MACH/MACL`, `PR` and `VBR` are accessed in memory; `PR` is a candidate for `x25-x28`
in call-heavy code. `pc`, `next_pc`, the delay flag and `data_ctx` are only stored at exits and before
call-outs, with the static values of section 11.

**Cycle accounting and exits.** Segment entry: `add x9, x21, #guard; cmp x9, x22; b.hs deopt_i` (near the
limit the threaded executor runs that segment checked); body without counting; `add x21, x21, #cost` once;
`cmp x21, x22; b.hs exit_stop`. Taken `BT`/`BF` add the static extra before their exit. Block entry also
checks `ticks_left >= n` and that the idle watch is outside the block (otherwise deopt, which handles
prefixes). Exit kinds returned in `w0`: `NEXT` (pc materialized, follow link), `STOP` (cycle limit),
`DEOPT` (resume threaded executor at op `i`), `INVALID` (code counter changed: block must be revalidated).

**Call-outs and synchronization points.** Every call-out is a potential synchronization point: a
communication-port read runs the other SH-2 inside the bus call, MMIO writes change VDP/PWM state, the
cache may change. Sequence: spill (dirty guest regs, `T`, counter), materialize `pc/next_pc/delay/data_ctx`,
`blr` helper(op index), reload counter/limit/cached regs, then `cmp x21, x22` stop check, `code` counter
compare (cache-touching call-outs), pc-continuation compare (control-flow call-outs). This keeps the exact
interpreter order of effects and makes every MMIO access, other-CPU sync and cache change an exit-capable
point.

**Chaining.** Blocks jump to successors through a per-block RW link table (`ldr x16, [table, #k]; br x16`),
so linking/unlinking never rewrites code (W^X stays strict: code pages are RW while filled, then RX). Each
target re-validates its `code` stamp at entry (one load + compare). Link targets default to a stub that
returns `NEXT` to the Rust dispatcher.

**Code arena.** One append-only `mmap` arena per CPU, `mprotect` RX after filling a batch, `__clear_cache`
on the written range; full reset (and all compiled pointers dropped) when full, on `conf` changes, state
load and reset (section 7). Blocks are compiled after N threaded executions (N ~ 16) so one-shot code stays
in the threaded backend.

**Phase (c) memory fast paths** (the measured mix makes them mandatory, see section 14): cached read hit
inline (entry index, 4 tag compares from `ways[w].tags[entry]`/`valid_bits`, LRU update through a 4-entry
and/or mask table, fetch-memo invalidation, big-endian word load from cache RAM), everything else a
call-out. Write-through stores stay call-outs (the bus write is required anyway).

**Verification of compiled code.** The `RecBus`/`ReplayBus` lockstep runs unchanged on compiled blocks; it
must run on an AArch64 host: `sh2-difftest` cross-compiled for `aarch64-linux-android` and run from
`/data/local/tmp` via `adb shell`, with the ROM and BIOS pushed from `E:/Sega` (never committed).

## 14. Results of the front half (host, x86-64, 2026-11-24)

**Exactness.** `sh2-difftest --mode verify`, 1800 frames per ROM with the oracle-style input scripts, on all
seven 32X suites (x_spiderman, x_mk2, doom, s32x/Virtua Racing Deluxe, x_shadow, x_carnage, x_motocross):
zero divergences over 1.32 billion compared blocks, 4.0 billion instructions and 426 million slices
(per-block register/counter/`data_ctx` checks, every bus access with its cycle stamp, a full serialized
CPU state compare every 64th slice). Independently, the per-frame hash chain of the full serialized
emulator state is identical between `--mode interp` and `--mode jit` for all seven ROMs over 1800 frames.
Random-program fuzzing: 18 837 programs bit-exact.

**Shape of the work** (verify runs, 1800 frames):

| ROM | instr per block run | native micro-ops | fast segments | chained runs |
|-----|------|------|------|------|
| Spider-Man | 3.6 | 61 % | 95 % | 50 % |
| Mortal Kombat II | 2.9 | 71 % | 88 % | 53 % |
| Doom | 2.6 | 90 % | 96 % | 84 % |
| Virtua Racing Deluxe | 3.1 | 87 % | 94 % | 70 % |
| Shadow Squadron | 2.6 | 92 % | 71 % | 36 % |
| Cosmic Carnage | 3.7 | 57 % | 97 % | 57 % |
| Motocross | 5.1 | 76 % | 92 % | 46 % |

**Speed of the threaded backend** (`--no-default-features` build, 600 frames, minimum of 5 alternating
runs, ms per emulated frame, whole emulator; SH-2 execution is about half of it):

| ROM | interpreter | block engine |
|-----|------|------|
| Mortal Kombat II | 4.46 | 5.12 (+15 %) |
| Doom | 4.32 | 5.03 (+17 %) |
| Virtua Racing Deluxe | 5.06 | 5.44 (+8 %) |
| Spider-Man | 5.29 | 5.95 (+12 %) |
| Shadow Squadron | 3.43 | 3.73 (+9 %) |
| Cosmic Carnage | 4.18 | 4.54 (+9 %) |
| Motocross | 4.56 | 4.83 (+6 %) |

The threaded backend is bit-exact but slower, like phase (a). Attribution experiment: forcing every
instruction through the call-out path (no native micro-ops at all) gives the same time as the normal
engine (MK2 5.38-5.73 vs 5.45-5.65, Doom 4.98-7.39 vs 5.14-7.75 ms/frame, same runs interleaved). So the
native micro-ops save nothing measurable: a threaded dispatch (one indirect jump or jump-table branch per
micro-op) costs about what the interpreter's memoized fetch plus table dispatch costs, and the block
machinery (lookup, validation, segment entry, exit materialization) every ~3 instructions is pure
overhead on top. Consequences for phase (b):

* the gain can only come from removing per-instruction dispatch entirely (straight-line machine code per
  segment), keeping guest registers and `T` in host registers across a segment, and fusing compare+branch;
* block entry/exit must cost a few instructions (stamp compare, counter guard), since blocks are entered
  every 2.5-5 instructions; chained transitions must not return to Rust;
* memory call-outs need inline cache-hit paths (phase c) because they are 8-43 % of executed
  instructions and every call-out spills and reloads state;
* the threaded engine remains the reference/deoptimization path and the verification harness carries
  over unchanged (run on device).

## 15. What remains for the ARM64 backend

1. Code arena for aarch64 Android/Linux (`mmap` RW, `mprotect` RX, `__clear_cache`), feature `jit-arm64`.
2. Segment compiler on top of `a64_translate` (all register-only instructions already translate and are
   checked against the interpreter in the AArch64 simulator): host-register allocation of guest registers
   and `T` within a segment, compare+branch fusion, static cycle add and guard check, exit stubs that
   materialize `pc`/`next_pc`/delay/`data_ctx` (values of section 11).
3. Branch micro-ops (`BT/BF` side exits, delayed branches with static or dynamic targets), link table
   chaining, entry checks (stamp, ticks, idle watch) with deopt to the threaded executor.
4. Call-out trampolines (`extern "C-unwind"`, monomorphized per bus type) with spill/reload and the
   post-call checks of section 13.
5. Device-side lockstep verification: cross-compile `sh2-difftest` for `aarch64-linux-android`, push
   ROM/BIOS to `/data/local/tmp`, run `--mode verify` and `--mode compare` on all seven suites, then the
   oracle suites with `jgenesis_sh2_jit=enabled`.
6. Phase (c): inline cached-read hits, then measure against the interpreter on the Lenovo and Anbernic.
