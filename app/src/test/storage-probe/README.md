# Direct PSP storage and screenshot validation

This probe is a separate APK (`com.sbro.emucorea.probe`). It uses the actual debug
build's DEX and native libraries, with an independent UID, document provider,
save directory and cache. It does not restart the main app, add games to its
library, or access its saves/account. Do not retarget its instrumentation at the
main package while a user is playing.

Build `:app:assembleDebug`, set `JAVA_HOME`, then run `run.ps1` with Android SDK,
demo and format fixture directories. The demo directory contains:

- `locoroco/EBOOT.PBP`: official free LocoRoco demo, downloaded from
  https://archive.org/download/PSPDemoArchive/UCES00304-1/EBOOT.PBP
  (index: https://psp.lusidgames.com/demos/main.html).
  SHA-256: `2eb423b801a8418d957341cae2a99ac35908eab478d49b1516ece5ac1f176bcf`.
  Its DATA.PSP has the encrypted `~PSP` header.
- `cavestory/EBOOT.PBP` and `cavestory/data.csz`: freeware PSP port from
  https://www.cavestory.one/downloads/cavestory-psp-rc1.zip.
  SHA-256, respectively:
  `19944ff16ac824b35a7036fa439fb470bf955f8e324d230fa3b1df5d33600f4f` and
  `69e860b5d0e501deb21331e64e61b0388e19bafd20960007ffced8707ae83d06`.

The fixture directory contains the existing homebrew cube fixture in ISO, CSO,
uncompressed CHD and CHD zlib/lzma/zstd formats, named `cube.iso`, `cube.cso`,
`cube.chd`, `cube-zlib.chd`, `cube-lzma.chd`, `cube-zstd.chd`. No game binaries
are committed. The test provider prepares its own source files once; the
emulator then opens provider descriptors directly, including `data.csz`.

Expected output: six SFO reads, case-insensitive sibling resolution, traversal
rejection, six boots with 240 paced frames each, a new provider open of
`data.csz` during emulation, and no legacy image cache directories. Android may
create an `oat_primary` bytecode cache; this is not an image copy. The preview
test calls the production bitmap factory and verifies exact RGB values after
PNG encoding with framebuffer alpha bytes 0, 16 and 32.

After testing, uninstall only `com.sbro.emucorea.probe`.

## Native regressions

Build `../cpp/storage_vfs_test.cpp` together with `../../main/cpp/storage_vfs.cpp`
using the Android NDK, the main CPP include directory and
`core/libretro/libretro-common/include`. It checks stream seek offsets, updating
existing files, 64-bit sparse file sizes, directory enumeration and failures.

`../cpp/core_vfs_test.cpp` additionally links `-ldl` and accepts the release core,
fixture directory, expected PSP achievement hash, and image names. For the
cube fixtures the hash is `b1dbd8757dd29524182e309931b8cc4a`. It catches the old
stdio/VFS seek-return mismatch through real core metadata and hashing readers.

## Verified 2026-09-20

- Release/debug builds, Android screenshot test compilation and 36 JVM tests passed.
- Both native VFS tests passed on arm64 Android; all six container hashes matched.
- The isolated Android probe passed the paced boots and PNG color regression.
- A clean main-app install booted a user-selected game and RetroAchievements
  worked; both were confirmed by the user.
- Full desktop C++/pspautotests suites were unavailable: MSBuild is not installed
  and `core/test.py -g --graphics=software` reports missing pspautotests submodule.

SAF game resources are read-only; ordinary PSP saves continue to use the writable
memstick directory. Homebrew that writes beside its executable needs additional
writable-provider support. Non-seekable providers fail instead of copying the
game. Regional cover mappings still have unresolved entries documented in the
cover repository's `review.json`; this storage change does not guess new serials.
