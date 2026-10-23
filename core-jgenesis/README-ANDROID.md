# EmuCoreH Android / libretro integration

Vendored jgenesis v0.14.1, upstream commit
`cbe7f129e3f5c805a2a2e4318981834192116e90` from
<https://github.com/jsgroth/jgenesis>. See `LICENSE` (GPLv3) and `UPSTREAM.txt`.
The original nested Git metadata is preserved locally at
`../build/jgenesis-upstream-git-20261023`, outside the vendored source directory.

`frontend/jgenesis-libretro` adds a separate libretro cdylib. Backend patches
expose Genesis pending battery-save persistence, add the equivalent SMS/GG
shutdown flush, and remove embedded 32X firmware in favor of explicit
user-supplied boot ROMs. Existing Flycast sources and build are independent.

## Build

Requires Android SDK / NDK **29.0.14206865**, Java 17+, Rust **1.98.1** and target
`aarch64-linux-android`. Windows uses the GNU Rust host so proc macros do not
require Visual Studio `link.exe`:

```powershell
rustup toolchain install 1.98.1-x86_64-pc-windows-gnu --profile minimal --target aarch64-linux-android
$env:PATH = "C:\msys64\ucrt64\bin;" + $env:PATH
cargo +1.98.1-x86_64-pc-windows-gnu test --locked --manifest-path core-jgenesis/Cargo.toml -p jgenesis-libretro
.\gradlew.bat :core-jgenesis-android:assembleDebug
```

On Linux/macOS install `1.98.1` with the Android target. The Gradle module chooses
the NDK host tools, builds with `--locked --release`, requests 16 KiB ELF page
alignment, and packages `lib/arm64-v8a/libjgenesis_libretro.so`. The core's
license and source metadata are included under `assets/jgenesis/`. Compiler
override: `-Pjgenesis.rustToolchain=...`; Windows GNU host-tools override:
`-Pjgenesis.hostToolsDir=...`. Cargo target files stay under the module's build
directory; no generated binary is checked into source control.

## Content and firmware

Genesis/Mega Drive (`md`, `gen`, `smd`, `bin`), 32X (`32x`), Master System
(`sms`), Game Gear (`gg`), SG-1000 (`sg`), Sega CD (`cue` + tracks or `chd`).
The upstream Genesis cartridge loader handles copier headers and SMD byte
layout. Generic BIN and ZIP cartridges also inspect SMS/GG/32X signatures.
ZIP accepts exactly one supported cartridge (64 MiB maximum); disc ZIPs and
multiple-cartridge ZIPs must be extracted. No 7z or M3U parser is included.
The frontend can materialize playlists and select individual disc paths.

The identified Sonic Classic Collection Nintendo DS-derived image (CRC32
`27E22795`, 512 KiB, verified emulator-hook signatures) is rejected with
`UNSUPPORTED_DS_COLLECTION_ROM` rather than hanging on a black screen. It contains
CPU trap hooks for that collection's emulator and needs the original Mega Drive
release instead. This check applies to plain/ZIP ROMs, uses full-image identity
and instruction signatures, and never rewrites ROM bytes or guesses from names.

The protected GG Aleste 3 Aleste Collection image (CRC32 `5AE64731`, 1 MiB,
verified boot/header signatures) requires its original collection emulator and
is rejected with `UNSUPPORTED_ALESTE_COLLECTION_ROM`. It uses a normal Sega
mapper but intentionally altered code; this is not a missing mapper feature.
The [original extractor and reverse engineers](https://www.smspower.org/forums/18250-GGAleste3)
identify that exact protected dump. Other images, including compatible Game Gear
variants, are not rejected by title, filename or header alone. No patches or
retail ROM data are distributed with the core; its regression fixture is authored
synthetic data.

Game Gear cartridges wired for SMS mode (cartridge pin 42) are selected by a
pinned size/CRC database from MAME's CC0 `gamegear.xml`, rather than filenames or
the inconclusive Sega header. This includes Castle of Illusion; its verified
Japanese `h1` variant also requires matching boot/header signatures. These games
retain Game Gear frontend/save-state identity while using SMS video, palette,
aspect ratio and Start/Pause handling. Their complete SMS frame is always shown;
the GG crop/resolution option cannot force a native-GG crop. Legacy states made
with the incorrect native-GG hardware mode are rejected without replacing the
running session. Other Game Gear images retain native hardware selection.

Sega CD BIOS is user supplied: exactly 131072 bytes, named `bios_CD_U.bin`,
`bios_CD_E.bin`, or `bios_CD_J.bin` in the system directory. Names are case
insensitive; additional regional aliases are accepted. A mismatched regional
BIOS is not substituted silently. Optional SMS/GG BIOS is not used.

32X firmware is user supplied and is never embedded or backed up by the app.
The system directory must contain `32X_G_BIOS.bin` (256 bytes),
`32X_M_BIOS.bin` (2048 bytes), and `32X_S_BIOS.bin` (1024 bytes), each with the
canonical CRC32 for its role. The core requires all three before creating 32X or
CD 32X hardware and reports `SEGA_32X_BIOS_REQUIRED` with the failing role when
any file is missing, oversized, truncated, or mismatched. The Android BIOS Folder
card scans any number of user-selected folders and installs every valid
Dreamcast, arcade, Sega CD and 32X file together. Selected folders are kept as a
list so users can import from multiple locations; installed firmware is
validated again before atomically replacing its private copy. No firmware bytes
are written into save states, and hard reset / state loading retain the active
per-instance firmware. No public-release or retail-game compatibility claim is
implied by synthetic smoke tests. Upstream CD 32X emulation is partial.

## ABI and runtime

Standard lifecycle, callbacks, full-path content loading, XRGB8888 software
video, 44100 Hz stereo audio, joypads and serialization are implemented.
`retro_get_region` reports PAL/NTSC. `retro_load_game_special` returns false.
Save states have shim/version/platform identification followed by upstream
fixed-integer little-endian bincode data. Frontend state containers additionally
need core and game identity. Battery saves use the frontend save directory and
are synced to a temporary file before atomic replacement; session shutdown
flushes pending RAM. Hidden `GET_VARIABLE` key `jgenesis_save_id` allows the
frontend to supply a stable ASCII letters/digits/underscore/hyphen identity
(1..128 bytes) when SAF content is materialized under a different file name.

Sega CD registers the extended disk-control interface when available (environment
commands 57/58), with the standard interface (command 13) as a fallback. Image
slots support append, replace, remove, select while ejected, and close tray;
image paths/labels let frontends reuse existing slots. Failed replacement allows
rollback to the previous image and removal of the appended slot. Save identity
remains unchanged during a swap. The frontend owns playlist UI.
`jgenesis_probe_disc(path)` is read only: returns 1 for a decoded Sega CD boot
signature, 2 for CD 32X, or 0 for unrecognized/unreadable content. Extensionless
CHD paths, including `/proc/self/fd/N`, are recognized from `MComprHD` magic.
`jgenesis_boot_error()` and `jgenesis_game_title()` expose borrowed C strings
valid until the next corresponding lifecycle/error update.

Genesis RetroPad mapping: Y→A, B→B, A→C, L→X, X→Y, R→Z,
Select→Mode, Start→Start. SMS/GG/SG-1000: B/Y→1, A/X→2, Start→Pause.
Two joypads are polled. Mouse, multitap, cheats, achievements, hardware rendering
and direct memory exposure are not implemented and must not be advertised.

Options (`SET_VARIABLES`, command 16):

| Key | Values / default |
| --- | --- |
| `jgenesis_region` | `auto`, `americas`, `europe`, `japan` (restart) |
| `jgenesis_timing` | `auto`, `ntsc`, `pal` (restart) |
| `jgenesis_remove_sprite_limit` | `disabled`, `enabled` |
| `jgenesis_genesis_lpf` | `enabled`, `disabled` |
| `jgenesis_sms_fm` | `enabled`, `disabled` |
| `jgenesis_sms_crop_vertical_border` | `enabled`, `disabled` |
| `jgenesis_gg_sms_resolution` | `disabled`, `enabled` |

The first value is the default. Non-restart options reload on a frontend
variable-update notification. Region/timing select hardware at game creation.

## Verification scope

The shim tests use original synthetic CPU programs, cartridge headers, RAM
writes and disc sectors. They check libretro boot/frame/audio callbacks,
state round trips and platform rejection, pad mapping, ZIP ambiguity, BIOS
lookup, decoded disc classification, atomic save replacement and shutdown RAM
flush. Retail Sega CD / 32X boot, CDDA/PCM/PWM correctness, disc changes during
gameplay, regional games and Android performance still require device/content
validation. No copyrighted retail game or Sega CD BIOS is needed by the suite.
The 32X boot test also covers the actionable missing-firmware path; when
`JGENESIS_TEST_32X_BIOS_DIR` (or the local `build/qa-32x-bios` QA directory) is
present, it additionally boots 32X with user-supplied firmware.
