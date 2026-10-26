# QA native backend preview

This experimental build is excluded from default features and production frontend options.
Build `jgenesis-libretro` with `--features jit-preview`. The libretro
`GET_VARIABLE` key `jgenesis_jit_backend` accepts `interpreter` (default) or
`native`. It is queried at each `retro_run` boundary. Genesis, Sega CD main/sub
68000 and 32X master/slave SH-2 CPU instances use separate host executor caches.
Host caches are absent from CPU clone/save data and are invalidated after content,
reset, or state changes. Nested CPU bus callbacks do not retain a registry borrow.

QA exports (call from the emulation thread between frames):

```c
size_t jgenesis_jit_snapshot_json(char *output, size_t capacity);
void jgenesis_jit_reset_counters(void);
```

Snapshot returns required capacity **including the trailing NUL**. A null or
undersized buffer is left untouched. Allocate that capacity and call again.
Reset clears counters while preserving warmed native code. The JSON schema is:

```json
{"schema":1,"backend":"native","cpus":[{"cpu":"m68k-main","native":0,"fallback":1,"guard_exits":0,"budget_exits":0,"stop_exits":0,"opcodes":[[9212,0,1]]}]}
```

All four CPU records are present: `m68k-main`, `m68k-sub`, `sh2-master`,
`sh2-slave`. Opcode tuples are `[decimal_opcode, native_executions,
fallback_executions]`. Counters report executed instruction bodies; an entire
instruction implemented by a Rust helper remains fallback. Bus reads/writes by
helpers alone do not disqualify a translated instruction. Reset/IRQ/DMA/halt
work is outside instruction counters. Unsupported hosts report fallback honestly.
These measurements describe executed workloads, not full legal ISA coverage.

SH-2 records additionally expose `dispatch` with `leaf`, `guarded`, `fast`,
and `slices`. The first three count executed native instructions in individual
native bodies, ordinary guarded blocks, and direct/cache fast blocks respectively.
Their sum must equal that CPU's `native` count. `slices` counts runtime native
entry calls, including reset/IRQ calls that execute no instruction. These fields
are absent from the 68000 records. A zero fallback count alone does not prove
that a workload used efficient native blocks.

The default build contains no preview exports, options, profile counters, runtime
backend checks, or executor registry. Native compilation and the diagnostics are
selected together only in this QA feature. This feature does not enable a
production JIT option.
