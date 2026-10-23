# EmuCoreH Android / libretro integration

Vendored jgenesis v0.14.1, upstream commit
`cbe7f129e3f5c805a2a2e4318981834192116e90` from
<https://github.com/jsgroth/jgenesis>. See `LICENSE` (GPLv3) and `UPSTREAM.txt`.
The original nested Git metadata is preserved locally at
`../build/jgenesis-upstream-git-20261023`, outside the vendored source directory.

`frontend/jgenesis-libretro` adds a separate libretro cdylib. The only backend
patches expose Genesis pending battery-save persistence and add the equivalent
SMS/GG shutdown flush. Existing Flycast sources and build are independent.

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

Sega CD BIOS is user supplied: exactly 131072 bytes, named `bios_CD_U.bin`,
`bios_CD_E.bin`, or `bios_CD_J.bin` in the system directory. Names are case
insensitive; additional regional aliases are accepted. A mismatched regional
BIOS is not substituted silently. Optional SMS/GG BIOS is not used.

Upstream 32X boot/security ROM data remains present. Resolving firmware
distribution policy and GPL application/source distribution obligations is a
public-release prerequisite. No public-release or retail-game compatibility
claim is implied by synthetic smoke tests. Upstream CD 32X emulation is partial.

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
