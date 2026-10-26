# EmuCoreH

[![Get EmuCoreH on Google Play](https://img.shields.io/badge/Google%20Play-Get%20EmuCoreH-3DDC84?logo=googleplay&logoColor=white)](https://play.google.com/store/apps/details?id=com.sbro.emucoreh)
[![Support EmuCoreH on Patreon](https://img.shields.io/badge/Patreon-Support%20EmuCoreH-ff424d?logo=patreon&logoColor=white)](https://www.patreon.com/c/emucore/membership)
[![Join the EmuCoreH Discord](https://img.shields.io/badge/Discord-Join%20the%20server-5865F2?logo=discord&logoColor=white)](https://discord.com/invite/c5EBeNRpz2)

EmuCoreH is a Sega game library, launcher, and emulator frontend for Android. Its Compose interface includes [Flycast](https://github.com/flyinghead/flycast) for Dreamcast and arcade systems, [jgenesis](https://github.com/jsgroth/jgenesis) for Mega Drive / Genesis, Sega CD, Sega 32X, Master System, Game Gear, and SG-1000, [Supermodel](https://github.com/trzy/Supermodel) through the [Super3 Android port](https://github.com/izzy2lost/Super3) for Sega Model 3, and the pinned [Beetle Saturn libretro core](https://github.com/libretro-mirrors/beetle-saturn-libretro) for Saturn. All four cores are built with the app; no separate core download is required.

Website: [emucoreh.web.app](https://emucoreh.web.app/)

Google Play: [EmuCoreH on Google Play](https://play.google.com/store/apps/details?id=com.sbro.emucoreh)

![Status](https://img.shields.io/badge/Status-Active%20Development-blue)

The project is under active development. Use your own legally obtained games. Flycast emulates the Dreamcast without a BIOS file when no BIOS dump is installed.

## Highlights

- Four bundled emulation cores for ARM64 Android devices
- Flycast Vulkan, OpenGL ES, and software rendering; jgenesis software video presented through OpenGL ES; Model 3 OpenGL ES 3 and Saturn software video presented through OpenGL ES
- Platform-aware game library, catalog, cover art, search, filters, and per-game settings
- Home hub with shelves, recently played titles, and quick resume
- In-game overlay with rendering, speed, and save state controls, opened with the Back gesture
- System-specific touch controls and independent Sega gamepad profiles, including six-button Mega Drive controls
- Shared touch layout editor with platform-specific buttons, independent system layouts, and per-game overrides
- Save states with core/platform validation, Sega battery saves, Saturn backup RAM, Model 3 NVRAM, and Dreamcast VMU management
- Local and optional Drive backups, including Sega battery saves, Saturn backup RAM, and Model 3 NVRAM
- Flycast cheats, texture replacements, and RetroAchievements for compatible games
- Sega Game Genie / Action Replay cheats with platform- and revision-specific catalog packs
- Unsupported options are hidden for Sega games; optional Discord integration
- Localized interface in 18 languages for phones, tablets, and Android TV

## What This Repository Contains

This repository contains the Android application, Kotlin UI, C++ JNI frontend, vendored Flycast, jgenesis, Supermodel / Super3, and pinned Beetle Saturn sources, and their Android build modules. Games, user save data, account credentials, and BIOS dumps are not included. 32X startup firmware is not distributed; see the [jgenesis integration notes](core-jgenesis/README-ANDROID.md). Saturn requires an imported user-owned BIOS; source provenance, build instructions, and the local save-state dialect are documented in the [Saturn integration notes](core-saturn/README-ANDROID.md).

## Tech Stack

- Kotlin + Jetpack Compose
- Android DataStore and Room
- JNI bridge to native C++ built with CMake and the Android NDK
- Independently loaded Flycast, Rust jgenesis, Supermodel, and Beetle Saturn libretro libraries, driven by the app's frontend
- Vulkan and OpenGL ES rendering paths with a shader chain runtime
- RetroAchievements integration through rcheevos
- Optional Discord Social SDK integration

## Current App Scope

EmuCoreH is available on [Google Play](https://play.google.com/store/apps/details?id=com.sbro.emucoreh). The current source tree uses:

- `minSdk 26` (Android 8.0)
- `targetSdk 37`
- package id `com.sbro.emucoreh`
- source version `0.1.1` (version code `31`)
- ARM64 devices only

## Building Locally

### Requirements

- Android Studio with Android SDK and NDK configured
- JDK 17
- Android SDK 37 and Android NDK `29.0.14206865`
- CMake `3.30.5`
- Rust `1.98.1` and the `aarch64-linux-android` target
- On Windows, the Rust GNU toolchain and MSYS2 UCRT64 GCC (default path `C:/msys64/ucrt64/bin`)
- Your own local Firebase configuration when building with the Google Services plugin

### Rust Setup

On Windows:

```powershell
rustup toolchain install 1.98.1-x86_64-pc-windows-gnu
rustup target add aarch64-linux-android --toolchain 1.98.1-x86_64-pc-windows-gnu
```

On Linux or macOS, install toolchain `1.98.1` and add `aarch64-linux-android` to it. Gradle builds the Rust core automatically. Override `jgenesis.hostToolsDir` or `jgenesis.rustToolchain` as Gradle properties when your toolchain is installed elsewhere.

### Debug Build

```powershell
.\gradlew :app:assembleDebug
```

### Release Build

```powershell
.\gradlew :app:assembleRelease
```

Install `app/build/outputs/apk/debug/app-debug.apk` on an ARM64 Android device.

### Optional Discord SDK

Discord support is built when a compatible Discord Social SDK directory is supplied through `emucorex.discord.sdkDir` in `local.properties`, a Gradle property with the same name, or `DISCORD_SDK_DIR`. The directory must contain `include/discordpp.h`, `arm64-v8a/libdiscord_partner_sdk.so`, and `discord_partner_sdk.aar`. The SDK is not included in this repository.

## Project Structure

- `app/` Android application, Kotlin UI, and JNI frontend sources
- `app/src/main/cpp` Native bridge and core integration
- `app/src/main/res` Android resources and translations
- `core/` Vendored Flycast sources
- `core-android/` Gradle module that builds Flycast for Android
- `core-jgenesis/` Vendored jgenesis sources and the libretro wrapper
- `core-jgenesis-android/` Gradle module that builds and packages jgenesis
- `core-supermodel/` Vendored Supermodel / Super3 sources and the Android libretro adapter
- `core-supermodel-android/` Gradle module that builds and packages Model 3 support
- `core-saturn/` Pinned Beetle Saturn (Mednafen) libretro source, software renderer, and source notices
- `core-saturn-android/` Gradle module that builds and packages Saturn support
- `app/src/main/assets/catalog/` Bundled platform-aware game catalog and cover indexes
- `tools/` Local release, catalog, and cover tooling (not part of the app build)

## Supported Content

| System | Core | Content |
|---|---|---|
| Dreamcast | Flycast | GDI, CHD, CDI, CUE/BIN, ISO, M3U |
| Naomi, Naomi 2, Atomiswave, System SP | Flycast | Compatible ROM sets: ZIP, 7Z, DAT, LST, supported raw dumps |
| Sega Model 3 | Supermodel / Super3 | Recognized ZIP ROM sets matching the bundled Games.xml definitions; required parent ZIPs for clone sets |
| Sega Saturn | Beetle Saturn | CHD, CUE with referenced tracks, CCD, ISO, recognized BIN discs, TOC, M3U playlists |
| Mega Drive / Genesis | jgenesis | MD, GEN, SMD, recognized BIN cartridges, single-ROM ZIP |
| Sega 32X | jgenesis | 32X, recognized BIN cartridges, single-ROM ZIP |
| Sega CD | jgenesis | CHD, CUE with every referenced track; M3U playlists |
| Master System | jgenesis | SMS, recognized BIN cartridges, single-ROM ZIP |
| Game Gear | jgenesis | GG, recognized BIN cartridges, single-ROM ZIP |
| SG-1000 | jgenesis | SG, single-ROM ZIP |

Unknown disc/archive formats retain the existing Flycast route. GDI/CUE track files are grouped with their disc instead of appearing as separate games, and folder-per-game dumps take their title from the folder (for example `King of Fighters XI/disc.gdi`). Disc images kept in an `atomiswave`, `naomi`, `naomi2` or `systemsp` folder are grouped under that arcade platform; archives still use the bundled romset catalog. Sega CD 32X combined hardware is not advertised as a separate supported system.

### Sega Model 3

Keep each ROM set as its original ZIP with its recognized set filename, such as `srally2.zip`. Keep clone sets and their required parent ZIPs in the same selected game folder; do not extract or rename them. The app prepares only the selected set and its required parent chain when Android folder access requires a private copy. The bundled `Games.xml` supplies set metadata; games and firmware are not bundled.

Model 3 renders at native 496×384 with a 4:3 display aspect ratio through OpenGL ES 3. Save states and per-game NVRAM are supported, including NVRAM in local and optional Drive backups. Network cabinets, force feedback, cheats, and RetroAchievements are not currently supported for Model 3.

On an Anbernic RG556, Sega Rally 2, Daytona USA 2, and The Lost World have been checked with owner-supplied sets for rendered game scenes, nonzero audio, exact save-state restoration and subsequent 12-frame state/audio replay. Daytona USA 2 requires its original cabinet setting `TEST → GAME ASSIGNMENTS → LINK ID → SINGLE` when playing without linked cabinets. Invalid sets and graphics initialization failures have separate recovery checks. These checks cover three sets; the full catalog is not verified, and full-speed performance on the RG556 is not established.

### BIOS

Dreamcast can boot through Flycast HLE without an installed BIOS. Arcade ROM sets may require their matching firmware.

All BIOS files are imported through one **BIOS Folder** card in onboarding or Settings → Library. Select any folder containing your files; every valid BIOS in that folder is installed together. Tap the card again to add BIOS files from another folder, and remove folders from the list without touching the installed copies. The app keeps multiple BIOS source folders for users whose dumps are split across different locations.

Sega CD dumps with descriptive filenames are recognized by their console boot header and region, including word-swapped dumps. Previously selected folders are migrated in the background once; normal startup shows the cached library without waiting for recursive BIOS scans. Sega CD discs are identified through the authorized SAF descriptor on devices that block direct filesystem access.

- Dreamcast: `dc_boot.bin` (2 MiB) or `dc_bios.bin`, plus `dc_flash.bin` (128 KiB)
- Arcade: `naomi.zip`, `naomi2.zip`, `awbios.zip`, and other supported MAME BIOS sets
- Sega CD: `bios_CD_U.bin`, `bios_CD_E.bin`, or `bios_CD_J.bin` (128 KiB, regional aliases accepted)
- Sega 32X: `32X_G_BIOS.bin` (256 bytes), `32X_M_BIOS.bin` (2048 bytes), and `32X_S_BIOS.bin` (1024 bytes)
- Saturn: a validated 512 KiB Japan 1.00/1.01 or US/Europe BIOS, installed as `saturn_bios.bin`; descriptive source filenames are accepted

Sega CD and 32X firmware is validated before installation; 32X files must match their exact role size and CRC32. Missing or invalid firmware produces an actionable BIOS message and returns to the library instead of leaving the game on a black screen.

### Sega Controls and Settings

Mega Drive / Genesis, Sega CD, and 32X use A/B/C, X/Y/Z, Start, and Mode. Master System and Game Gear use two action buttons; Master System has Pause and Game Gear has Start. SG-1000 uses two action buttons without a Start/Pause button.

Model 3 uses Select/Coin, Start, L3/Service, R3/Test, and game-specific action buttons. Driving games use left-stick X or the D-pad to steer, R2 to accelerate, L2 to brake, and R1/L1 to shift up/down. Gun games have a left-stick aiming and action-button trigger/reload fallback; twin-stick games use both sticks. Controls can be remapped for the selected game.

Each Sega system has independent default touch and gamepad profiles. The shared controls editor supports the selected platform's buttons and saves global system layouts separately from per-game overrides. Global system options, per-game settings, and the in-game menu expose the options supported by the selected system. Sega saves and core settings are isolated from Flycast data. Game Gear defaults to its original screen proportions in Auto aspect mode.

Saturn uses A/B/C, X/Y/Z, L/R and Start with its own touch/gamepad profile, firmware directory and save-state namespace. The core renders software frames that the app presents through its OpenGL ES surface. Internal backup RAM, RTC data and cartridge NV are included in local and Drive backups. Firmware and retail game compatibility require verification with user-supplied content; a successful build alone does not establish full-speed compatibility.

## Notes

- Game images, user save data, account credentials, and user-supplied Dreamcast, Sega CD, and 32X BIOS dumps are not distributed with this project.
- Compatibility, performance, and graphics behavior vary by game, device, renderer, and driver stack.

## Credits and license

The root [LICENSE](LICENSE) contains Flycast's upstream GPL-2.0 text. Flycast and its dependencies retain their notices in `core/`. jgenesis is separately licensed under [GPL-3.0](core-jgenesis/LICENSE); its pinned revision, local changes, build instructions, and distribution notes are recorded in [UPSTREAM.txt](core-jgenesis/UPSTREAM.txt) and [README-ANDROID.md](core-jgenesis/README-ANDROID.md). The Android package includes the jgenesis license and source notices.

Supermodel / Super3 is separately licensed under [GPL-3.0](core-supermodel/LICENSE). The Android port is pinned to [Super3 commit c410f82](https://github.com/izzy2lost/Super3/tree/c410f82f771b33094b8c1cdbf96ef7c74ec9cff1); source provenance and local changes are recorded in [UPSTREAM.txt](core-supermodel/UPSTREAM.txt), with integration details in [README-ANDROID.md](core-supermodel/README-ANDROID.md). Its [third-party notices](core-supermodel/THIRD-PARTY-NOTICES.txt), component licenses, and source notices are preserved, and the Android package includes the license and provenance files. No game or BIOS rights are granted by the emulator license.

Saturn uses the GPL-2.0-or-later Beetle Saturn (Mednafen) libretro source at revision `dd18f9c477106263b3b7b050f4970d331ff7b23a`. The local module is built under GPL-3.0; its [source provenance](core-saturn/UPSTREAM.txt), [third-party notices](core-saturn/THIRD-PARTY-NOTICES.txt) and [Android notes](core-saturn/README-ANDROID.md) accompany the vendored source. Commercial redistribution requires satisfying the applicable license and corresponding-source obligations.

Thanks to the Flycast, jgenesis, Supermodel, Super3, and Beetle Saturn/Mednafen contributors, the RetroAchievements team for rcheevos, and IGDB for game metadata.

EmuCoreH is independent of Sega, Flycast, jgenesis, Supermodel, Super3, IGDB, Discord, and RetroAchievements. System names and game artwork belong to their respective owners.

## Support

If you want to support ongoing development:

- Patreon: [https://www.patreon.com/c/emucore/membership](https://www.patreon.com/c/emucore/membership)
- Discord: [https://discord.com/invite/c5EBeNRpz2](https://discord.com/invite/c5EBeNRpz2)
- More apps by the author: [Google Play developer page](https://play.google.com/store/apps/dev?id=7136622298887775989)
