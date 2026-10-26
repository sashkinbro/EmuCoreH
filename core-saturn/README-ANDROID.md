# Saturn native core

Pinned YabaSanshiro libretro core, Android ARM64, GLES 3.1 and SH2 dynarec.
The Gradle module `core-saturn-android` builds `libsaturn_libretro.so` using
NDK 29.0.14206865 and CMake 3.30.5. All sources are local and pinned; consult
UPSTREAM.txt and THIRD-PARTY-NOTICES.txt before updating them.

Provide a user-owned Saturn BIOS in the frontend system directory under
`saturn_bios.bin`, `sega_101.bin`, or `mpr-17933.bin`. HLE BIOS is disabled.
Supported disc inputs are CUE/BIN, ISO, MDS/MDF, CCD/IMG and CHD. Multidisc
selection uses local libretro tray callbacks and the app disc menu. The native
port extends upstream with a bounded 128-disc list and reports insertion errors
so the frontend can roll back to the previous disc. PAL regions select 50 Hz,
NTSC regions select 60 Hz, both with 4:3 display aspect.

Calls, graphics callbacks and state operations must use the frontend emulation
thread with the appropriate GLES context current. Context destruction precedes
unload safely. GL recreation restores the renderer while preserving emulated
CPU/RAM state. State buffers are available after the first emulated frame;
the upstream format advertises single-session serialization limitations.
The local state dialect keeps the YSS v2 envelope, with CS2 chunk v4, SH2 chunk v5, SCSP chunk v5 and
OTHR chunk v2. SCSP v5 additionally preserves the DSP delayed read/write,
multiply and shift pipeline used by active sound effects. Older incomplete SCSP
states are rejected. Musashi host callback
and cycle-table pointers are retained from the live core, never serialized.

Additional ABI: `saturn_boot_error()` gives the last initialization error;
`saturn_probe_disc(path)` / `saturn_probe_disc_fd(fd)` return 2 for a Saturn
disc signature and 0 otherwise. Probes use independent handles and preserve
the caller's FD cursor. No emulator is initialized for disc identification.

Optional QA audio ABI: `void saturn_reset_audio_fingerprint()` starts a fresh
FNV-1a 64-bit hash of the interleaved signed 16-bit stereo PCM bytes passed to
the frontend, with each sample hashed low byte first. `uint64_t
saturn_audio_fingerprint()`, `saturn_audio_samples()` and
`saturn_audio_nonzero_samples()` read the hash, total individual-channel sample
count and nonzero individual-channel sample count. Reset/read between frames
while no audio callback is running. Diagnostics remain disabled until explicitly
reset and are disabled again by `retro_init`; ordinary sessions do not hash PCM.

`tests/api_contract.c` is an Android native smoke test (link with libdl).
Pass the built core's path as its sole argument. It checks pre-frame state
rejection, missing probe inputs and independent raw-disc/FD identification.
The temporary probe disc contains only a self-authored format header.

`tests/disk_contract.c` links against `disc_control.c` with mocked tray calls.
Pass any readable file as its sole argument. It verifies tray-open constraints,
media replacement and failure rollback without loading copyrighted game data.

Verification on 2026-10-26: NDK29 release core linked without undefined symbols;
ELF LOAD segments have 0x4000 alignment. The API contract passed on RG556 and
TB710FU; the disk callback contract passed on TB710FU. These are native ABI and
callback checks, not claims of game compatibility or full-speed emulation.
