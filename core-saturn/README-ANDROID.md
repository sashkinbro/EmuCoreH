# Saturn native core

Pinned Beetle Saturn (Mednafen) libretro core, Android ARM64.
The Gradle module `core-saturn-android` builds `libsaturn_libretro.so` using
NDK 29.0.14206865 and CMake 3.30.5. All sources are local and pinned; consult
UPSTREAM.txt and THIRD-PARTY-NOTICES.txt before updating them.

Provide a user-owned Saturn BIOS in the frontend system directory. The app
installs its validated image as `saturn_bios.bin`; the core also accepts the
upstream `sega_101.bin` (Japan/Asia NTSC) and `mpr-17933.bin` (North America,
Europe and other regions) names, preferring the region-specific file when both
exist. HLE BIOS is not available. Supported disc inputs are CUE/BIN, CCD/IMG,
CHD, TOC and M3U. Multidisc selection uses the libretro disk-control interface
and the app disc menu; this port implements runtime add/replace/remove so a
tray swap does not relaunch the emulator. PAL regions select 50 Hz, NTSC
regions select 60 Hz, both with 4:3 display aspect.

The core renders software frames in XRGB8888 (up to 704x576). The frontend
uploads each frame to its OpenGL ES or Vulkan presentation surface, so no
core-side GL context is required. Calls, input, audio callbacks and state
operations must use the frontend emulation thread.

Backup RAM and RTC data use the upstream per-content names in the frontend
save directory: `<content>.bkr` and `<content>.smpc`. Cartridge NV data uses
`<content>.<ext>` when the selected cartridge has battery-backed storage.
Shared internal/backup memory is disabled by default.

Save states use Mednafen's `MDFNSVST` container with core version 0x00102202
(Beetle Saturn v1.22.2). States from another Saturn core, another core version
or another BIOS are rejected. The BIOS SHA-256 is stored in each state.

Additional ABI: `saturn_boot_error()` gives the last initialization error;
`saturn_probe_disc(path)` / `saturn_probe_disc_fd(fd)` return 2 for a Saturn
disc signature and 0 otherwise; `saturn_disc_metadata(...)` returns the boot
header title and product code. Probes use independent handles and preserve the
caller's FD cursor. No emulator is initialized for disc identification.

The upstream option table is available under the `beetle_saturn_` prefix.
The frontend exposes cartridge, region, BIOS language, CD image cache and
mid-frame input synchronization options.
