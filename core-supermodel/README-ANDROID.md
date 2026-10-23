# Supermodel / Super3 libretro for Android ARM64

Pinned source and license provenance are in UPSTREAM.txt and
THIRD-PARTY-NOTICES.txt. GPL v3 applies to this emulator library. No game
content or prebuilt core binary is supplied.

The frontend owns EGL, input polling, frame pacing, audio output, storage and
Android lifecycle. This library exports standard libretro API v1 and the
optional `const char* supermodel_boot_error()` diagnostic. It supports full
path ZIP sets (need_fullpath=true, block_extract=true). System metadata must
be installed at `<system_directory>/supermodel/Games.xml`. Parent/clone ZIPs
should remain together for upstream ROM lookup. Supported set definitions
are from the pinned XML and are not a full compatibility guarantee.

Hardware rendering requests RETRO_HW_CONTEXT_OPENGLES3, depth and stencil,
bottom-left origin, with context_reset and context_destroy. Every final
frame targets get_current_framebuffer(); the frontend performs presentation.
AV: native 496x384, display aspect 4:3, 60 frames/sec, stereo S16 at 44100Hz.
The sound core produces 735 samples/frame. Core execution is synchronous
with no separate sound/device thread. Native resolution and the simpler
Android New3D shader path are selected; CPU-intensive games may run slowly.
Fatal graphics initialization failures and frame exceptions latch a runtime
failure and send RETRO_ENVIRONMENT_SHUTDOWN with the diagnostic retained in
supermodel_boot_error(). Frames remain stopped until a new game load. A
missing/destroyed context waits normally; load rejection and NVRAM write
diagnostics do not request runtime shutdown.

Inputs: two libretro joypads, analog sticks and optional lightgun devices.
Select inserts a coin, Start starts, L3 is Service, R3 is Test. A/B/X/Y map
to game action buttons. For driving titles: left-stick X or d-pad steers,
R2 accelerates, L2 brakes, R/L shift up/down, A changes view. For twin-stick
titles both analog sticks supply the two sticks; L2/R2 fire and L/R turbo.
Lightgun device coordinates use SCREEN_X/Y, TRIGGER and RELOAD/OFFSCREEN.
Joypad left-stick plus app A/B is an aiming/trigger/reload fallback. The Android
frontend's existing libretro face-button ABI is Cross=B, Circle=A, Square=Y,
Triangle=X; this adapter translates that order to the app's A/B/X/Y labels.
Model 3 receives 0..255 hardware L2/R2 pressure through ANALOG_BUTTON inputs;
digital touch/key pedals retain full-pressure fallback. Analog pedal releases
are immediate and do not use the frontend's digital tap-hold queue.

NVRAM is atomically persisted at `<save>/supermodel/<set>/nvram.bin`.
Libretro serialization wraps upstream CBlockFile bytes in an 80-byte
versioned header (magic M3RETRO1, uint32 LE version=1, payload length,
FNV1a checksum, zero-terminated ROM-set name at offset20). Payload begins
with uint32 LE selected gear, followed by CBlockFile data. Frames/state work
stay on the emulation worker. Temporary per-set files are removed after
serialization. State buffers are limited to 64MiB; corruption, another set,
truncation and invalid block lengths are rejected before legacy readers.
The format is specific to this pinned adapter/core build. Additional blocks
preserve pending CPU interrupts, sound timing, SCSP history, DSB resampler and
MPEG decoder history, guest RTC and frontend gear-input edges. Imported blocks
must match the current game's layout and pass scalar/range validation before
the running machine is modified. Decoder snapshots store ROM-relative offsets,
never host pointers.

On an RG556, owner-supplied `srally2`, `daytona2` and `lostwsga` sets have
rendered game scenes and nonzero audio. Exact paused state restoration,
corrupt-state rejection, identical 12-frame future state, nonzero PCM replay
and held-shift restoration have separate device checks. Daytona requires
its original cabinet menu `TEST → GAME ASSIGNMENTS → LINK ID → SINGLE`
without linked cabinets. Tile layers capture live memory before each visible
scanline's CPU execution; IRQ2 remains at line 383. The software tile surfaces
preserve displayed rows when the guest clears VRAM late in the frame.
These are three tested sets, not a compatibility claim for every XML entry
or a complete release/lifecycle qualification.

Build with Android NDK29 and CMake, Android ABI arm64-v8a, API26 minimum.
No SDL dependency or network download is needed. ELF load alignment is 16KiB.
Standalone CMake builds can enable MODEL3_BUILD_LIFECYCLE_TESTS to produce
`model3_no_content_lifecycle` (run it with core .so path and a temporary
directory). That checks ABI/failure paths only, not a game boot. Host tests
under libretro/tests cover input scaling, corrupt-state boundaries, audio
mixing, scene-memory bounds, the guest calendar, actual portable threading
primitives and exact nonzero MPEG replay using a self-authored stereo fixture.
They also exercise the production runtime guard's graphics failures, thrown
initialization/frame work, normal context waits, fatal latch and reload reset.
The same CMake option builds `model3_runtime_failure_lifecycle`; invoke it as
`model3_runtime_failure_lifecycle core.so system_directory owner_set.zip temporary_save_directory`.
It uses a GLES3 pbuffer and the core's normal callbacks to exercise real shader
initialization failure without a current EGL context, shutdown propagation,
callback suppression and reload recovery. It requires owner-supplied content
and is not a production crash interface. Exception-catching coverage is in
the same-ELF host runtime-guard test; the Android executable does not throw
C++ exceptions across separately linked static C++ runtimes.

Not implemented: network cabinets, force feedback, cheats, RetroAchievements,
Vulkan, enhanced multipass effects, frontend hotkey/config menus. These
features are not advertised through the core API.
