# EmuCoreH

[![Support EmuCoreH on Patreon](https://img.shields.io/badge/Patreon-Support%20EmuCoreH-ff424d?logo=patreon&logoColor=white)](https://www.patreon.com/c/emucore/membership)
[![Join the EmuCoreH Discord](https://img.shields.io/badge/Discord-Join%20the%20server-5865F2?logo=discord&logoColor=white)](https://discord.com/invite/c5EBeNRpz2)
[![Website](https://img.shields.io/badge/Website-emucorea.web.app-1f6feb?logo=googlechrome&logoColor=white)](https://emucorea.web.app/)

EmuCoreH is a Sega Dreamcast, Naomi, Naomi 2, and Atomiswave library, launcher, and emulator frontend for Android. It pairs a purpose-built Compose interface with a vendored [Flycast](https://github.com/flyinghead/flycast) core that is built together with the app, so no separate core download is needed.

Official website: [https://emucorea.web.app/](https://emucorea.web.app/)

![Status](https://img.shields.io/badge/Status-Active%20Development-blue)

The project is under active development. Use your own legally obtained games. Flycast emulates the Dreamcast without a BIOS file when no BIOS dump is installed.

## Highlights

- Flycast-based emulation core built together with the app for ARM64 devices
- Vulkan, OpenGL ES, and software rendering with internal resolution controls
- Game library with Dreamcast and arcade title detection, cover art, search, and per-game settings
- Home hub with shelves, recently played titles, and quick resume
- In-game overlay with rendering, speed, and save state controls, opened with the Back gesture
- Touch controls with a layout editor, plus physical gamepad support
- Save states, VMU and memory card management
- Cheat and texture replacement support for compatible games
- RetroAchievements and optional Discord integration
- Localized interface in 18 languages for phones, tablets, and Android TV

## What This Repository Contains

This repository contains the Android application, its Kotlin UI, the JNI frontend, the vendored Flycast sources, and the Gradle module that builds the emulation core for Android. No games, BIOS files, save data, or account credentials are included.

## Tech Stack

- Kotlin + Jetpack Compose
- Android DataStore and Room
- JNI bridge to native C++ built with CMake and the Android NDK
- Vendored Flycast core built as a libretro library and driven by the app's own frontend
- Vulkan and OpenGL ES rendering paths with a shader chain runtime
- RetroAchievements integration through rcheevos
- Optional Discord Social SDK integration

## Current App Scope

EmuCoreH version `0.0.1` currently targets Android with:

- `minSdk 26` (Android 8.0)
- `targetSdk 37`
- package id `com.sbro.emucoreh`
- version `0.0.1`
- ARM64 devices only

## Building Locally

### Requirements

- Android Studio with Android SDK and NDK configured
- JDK 17
- Android SDK 37 and Android NDK `29.0.14206865`
- CMake `3.30.5`

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
- `core-android/` Gradle module that builds the Flycast core for Android
- `tools/` Local release, catalog, and cover tooling (not part of the app build)

## Supported Content

CHD, CDI, GDI, and CUE/BIN images are the main game formats. LST, DAT, ZIP, 7Z, and M3U are supported as well. Arcade content for Naomi, Naomi 2, and Atomiswave runs from ROM sets. Dreamcast BIOS files are optional: without one the core boots through its HLE BIOS. No games, BIOS files, save data, or account credentials are included here.

## Notes

- Game images, BIOS files, save data, and account credentials are not distributed with this project.
- Compatibility, performance, and graphics behavior vary by game, device, renderer, and driver stack.

## Credits and license

EmuCoreH builds on Flycast. The root [LICENSE](LICENSE) is an exact copy of Flycast's upstream license file (GPL-2.0). The vendored core and its dependencies retain their copyright and license notices in `core/`.

Thanks to the Flycast contributors and to the RetroAchievements team for rcheevos.

EmuCoreH is independent of Sega, Flycast, IGDB, Discord, and RetroAchievements. Dreamcast, Naomi, Naomi 2, and Atomiswave are trademarks of Sega. Game artwork and game data belong to their respective owners.

## Support

If you want to support ongoing development:

- Website: [https://emucorea.web.app/](https://emucorea.web.app/)
- Patreon: [https://www.patreon.com/c/emucore/membership](https://www.patreon.com/c/emucore/membership)
- Discord: [https://discord.com/invite/c5EBeNRpz2](https://discord.com/invite/c5EBeNRpz2)
- More apps by the author: [Google Play developer page](https://play.google.com/store/apps/dev?id=7136622298887775989)
