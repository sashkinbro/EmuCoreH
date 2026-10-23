# EmuCoreH

[![Get EmuCoreH on Google Play](https://img.shields.io/badge/Google%20Play-Get%20EmuCoreH-3DDC84?logo=googleplay&logoColor=white)](https://play.google.com/store/apps/details?id=com.sbro.emucoreh)
[![Support EmuCoreH on Patreon](https://img.shields.io/badge/Patreon-Support%20EmuCoreH-ff424d?logo=patreon&logoColor=white)](https://www.patreon.com/c/emucore/membership)
[![Join the EmuCoreH Discord](https://img.shields.io/badge/Discord-Join%20the%20server-5865F2?logo=discord&logoColor=white)](https://discord.com/invite/c5EBeNRpz2)

EmuCoreH is a Sega game library, launcher, and emulator frontend for Android. Its Compose interface includes [Flycast](https://github.com/flyinghead/flycast) for Dreamcast and arcade systems, and [jgenesis](https://github.com/jsgroth/jgenesis) for Mega Drive / Genesis, Sega CD, Sega 32X, Master System, Game Gear, and SG-1000. Both cores are built with the app; no separate core download is required.

Website: [emucoreh.web.app](https://emucoreh.web.app/)

Google Play: [EmuCoreH on Google Play](https://play.google.com/store/apps/details?id=com.sbro.emucoreh)

![Status](https://img.shields.io/badge/Status-Active%20Development-blue)

The project is under active development. Use your own legally obtained games. Flycast emulates the Dreamcast without a BIOS file when no BIOS dump is installed.

## Highlights

- Two bundled emulation cores for ARM64 Android devices
- Flycast Vulkan, OpenGL ES, and software rendering; jgenesis software video presented through OpenGL ES
- Platform-aware game library, catalog, cover art, search, filters, and per-game settings
- Home hub with shelves, recently played titles, and quick resume
- In-game overlay with rendering, speed, and save state controls, opened with the Back gesture
- System-specific touch controls and independent Sega gamepad profiles, including six-button Mega Drive controls
- Shared touch layout editor with platform-specific buttons, independent system layouts, and per-game overrides
- Save states with core/platform validation, Sega battery saves, and Dreamcast VMU management
- Local and optional Drive backups, including Sega battery saves
- Flycast cheats, texture replacements, and RetroAchievements for compatible games
- Unsupported options are hidden for Sega games; optional Discord integration
- Localized interface in 18 languages for phones, tablets, and Android TV

## What This Repository Contains

This repository contains the Android application, Kotlin UI, C++ JNI frontend, vendored Flycast and jgenesis sources, and their Android build modules. Games, user save data, account credentials, Dreamcast BIOS dumps, and Sega CD / 32X BIOS dumps are not included. 32X startup firmware is not distributed; see the [Android integration notes](core-jgenesis/README-ANDROID.md).

## Tech Stack

- Kotlin + Jetpack Compose
- Android DataStore and Room
- JNI bridge to native C++ built with CMake and the Android NDK
- Independently loaded Flycast and Rust jgenesis libretro libraries, driven by the app's frontend
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
- `app/src/main/assets/catalog/` Bundled platform-aware game catalog and cover indexes
- `tools/` Local release, catalog, and cover tooling (not part of the app build)

## Supported Content

| System | Core | Content |
|---|---|---|
| Dreamcast | Flycast | GDI, CHD, CDI, CUE/BIN, ISO, M3U |
| Naomi, Naomi 2, Atomiswave, System SP | Flycast | Compatible ROM sets: ZIP, 7Z, DAT, LST, supported raw dumps |
| Mega Drive / Genesis | jgenesis | MD, GEN, SMD, recognized BIN cartridges, single-ROM ZIP |
| Sega 32X | jgenesis | 32X, recognized BIN cartridges, single-ROM ZIP |
| Sega CD | jgenesis | CHD, CUE with every referenced track; M3U playlists |
| Master System | jgenesis | SMS, recognized BIN cartridges, single-ROM ZIP |
| Game Gear | jgenesis | GG, recognized BIN cartridges, single-ROM ZIP |
| SG-1000 | jgenesis | SG, single-ROM ZIP |

Unknown disc/archive formats retain the existing Flycast route. CUE track files are grouped with their disc instead of appearing as separate games. Sega CD 32X combined hardware is not advertised as a separate supported system.

### BIOS

Dreamcast can boot through Flycast HLE without an installed BIOS. Arcade ROM sets may require their matching firmware.

Sega CD requires your own **128 KiB BIOS** for the disc region. In Settings, select Sega CD, open Library, and import the BIOS into the matching US, Europe, or Japan slot. The app uses `bios_CD_U.bin`, `bios_CD_E.bin`, and `bios_CD_J.bin` internally.

Sega 32X requires your own three firmware files: `32X_G_BIOS.bin` (256 bytes), `32X_M_BIOS.bin` (2048 bytes), and `32X_S_BIOS.bin` (1024 bytes). In Settings, select Sega 32X and import each file; exact size and CRC32 role validation is applied before installation. Missing or invalid firmware produces an actionable BIOS message instead of starting an unrelated system.

### Sega Controls and Settings

Mega Drive / Genesis, Sega CD, and 32X use A/B/C, X/Y/Z, Start, and Mode. Master System and Game Gear use two action buttons; Master System has Pause and Game Gear has Start. SG-1000 uses two action buttons without a Start/Pause button.

Each Sega system has independent default touch and gamepad profiles. The shared controls editor supports the selected platform's buttons and saves global system layouts separately from per-game overrides. Global system options, per-game settings, and the in-game menu expose the options supported by the selected system. Sega saves and core settings are isolated from Flycast data. Game Gear defaults to its original screen proportions in Auto aspect mode.

## Notes

- Game images, user save data, account credentials, and user-supplied Dreamcast, Sega CD, and 32X BIOS dumps are not distributed with this project.
- Compatibility, performance, and graphics behavior vary by game, device, renderer, and driver stack.

## Credits and license

The root [LICENSE](LICENSE) contains Flycast's upstream GPL-2.0 text. Flycast and its dependencies retain their notices in `core/`. jgenesis is separately licensed under [GPL-3.0](core-jgenesis/LICENSE); its pinned revision, local changes, build instructions, and distribution notes are recorded in [UPSTREAM.txt](core-jgenesis/UPSTREAM.txt) and [README-ANDROID.md](core-jgenesis/README-ANDROID.md). The Android package includes the jgenesis license and source notices.

Thanks to the Flycast and jgenesis contributors, the RetroAchievements team for rcheevos, and IGDB for game metadata.

EmuCoreH is independent of Sega, Flycast, jgenesis, IGDB, Discord, and RetroAchievements. System names and game artwork belong to their respective owners.

## Support

If you want to support ongoing development:

- Patreon: [https://www.patreon.com/c/emucore/membership](https://www.patreon.com/c/emucore/membership)
- Discord: [https://discord.com/invite/c5EBeNRpz2](https://discord.com/invite/c5EBeNRpz2)
- More apps by the author: [Google Play developer page](https://play.google.com/store/apps/dev?id=7136622298887775989)
