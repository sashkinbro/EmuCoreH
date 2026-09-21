# SPDX-FileCopyrightText: 2026 SBRO
# SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
cmake_minimum_required(VERSION 3.22)
project(EmuCoreHAndroid LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# The emulator core (Flycast, vendored in /core) is built by the :core-android
# Gradle module and shipped as flycast_libretro.so. This target is the
# EmuCoreH frontend bridge; it loads the core at runtime through dlopen and
# drives it via the libretro API.
set(CORE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../core")
set(RCHEEVOS_DIR "${CORE_DIR}/ext/rcheevos")
set(LIBRETRO_COMMON_DIR "${CORE_DIR}/core/deps/libretro-common/include")

add_library(rcheevos_frontend STATIC
    "${RCHEEVOS_DIR}/src/rc_client.c"
    "${RCHEEVOS_DIR}/src/rc_compat.c"
    "${RCHEEVOS_DIR}/src/rc_libretro.c"
    "${RCHEEVOS_DIR}/src/rc_util.c"
    "${RCHEEVOS_DIR}/src/rc_version.c"
    "${RCHEEVOS_DIR}/src/rapi/rc_api_common.c"
    "${RCHEEVOS_DIR}/src/rapi/rc_api_editor.c"
    "${RCHEEVOS_DIR}/src/rapi/rc_api_info.c"
    "${RCHEEVOS_DIR}/src/rapi/rc_api_runtime.c"
    "${RCHEEVOS_DIR}/src/rapi/rc_api_user.c"
    "${RCHEEVOS_DIR}/src/rcheevos/alloc.c"
    "${RCHEEVOS_DIR}/src/rcheevos/condition.c"
    "${RCHEEVOS_DIR}/src/rcheevos/condset.c"
    "${RCHEEVOS_DIR}/src/rcheevos/consoleinfo.c"
    "${RCHEEVOS_DIR}/src/rcheevos/format.c"
    "${RCHEEVOS_DIR}/src/rcheevos/lboard.c"
    "${RCHEEVOS_DIR}/src/rcheevos/memref.c"
    "${RCHEEVOS_DIR}/src/rcheevos/operand.c"
    "${RCHEEVOS_DIR}/src/rcheevos/rc_validate.c"
    "${RCHEEVOS_DIR}/src/rcheevos/richpresence.c"
    "${RCHEEVOS_DIR}/src/rcheevos/runtime.c"
    "${RCHEEVOS_DIR}/src/rcheevos/runtime_progress.c"
    "${RCHEEVOS_DIR}/src/rcheevos/trigger.c"
    "${RCHEEVOS_DIR}/src/rcheevos/value.c"
    "${RCHEEVOS_DIR}/src/rhash/aes.c"
    "${RCHEEVOS_DIR}/src/rhash/cdreader.c"
    "${RCHEEVOS_DIR}/src/rhash/hash.c"
    "${RCHEEVOS_DIR}/src/rhash/hash_disc.c"
    "${RCHEEVOS_DIR}/src/rhash/hash_encrypted.c"
    "${RCHEEVOS_DIR}/src/rhash/hash_rom.c"
    "${RCHEEVOS_DIR}/src/rhash/hash_zip.c"
    "${RCHEEVOS_DIR}/src/rhash/md5.c"
)
target_include_directories(rcheevos_frontend PUBLIC "${RCHEEVOS_DIR}/include" "${RCHEEVOS_DIR}/src")
target_include_directories(rcheevos_frontend PRIVATE "${LIBRETRO_COMMON_DIR}")
target_compile_definitions(rcheevos_frontend PUBLIC RC_CLIENT_SUPPORTS_HASH)

# The Discord Social SDK is optional and runs in the isolated :discord process.
if(DISCORD_SDK_DIR)
    set(DISCORD_SDK_SO "${DISCORD_SDK_DIR}/${ANDROID_ABI}/libdiscord_partner_sdk.so")
    set(DISCORD_SDK_INCLUDE "${DISCORD_SDK_DIR}/include")
    if(EXISTS "${DISCORD_SDK_SO}" AND EXISTS "${DISCORD_SDK_INCLUDE}/discordpp.h")
        add_library(discord_partner_sdk SHARED IMPORTED)
        set_target_properties(discord_partner_sdk PROPERTIES IMPORTED_LOCATION "${DISCORD_SDK_SO}")
        add_library(emucoreh_discord SHARED discord_bridge.cpp)
        target_include_directories(emucoreh_discord PRIVATE "${DISCORD_SDK_INCLUDE}")
        target_link_libraries(emucoreh_discord PRIVATE discord_partner_sdk log)
        set_target_properties(emucoreh_discord PROPERTIES OUTPUT_NAME "emucoreh_discord")
    endif()
endif()

add_library(emucoreh_jni SHARED
    native_bridge.cpp
    storage_vfs.cpp
    achievements_bridge.cpp
    shader_effect.cpp
    shader_chain.cpp
    shader_chain_present.cpp
    vulkan_frontend.cpp
)

# Build the same pinned RetroArch .slangp runtime used by EmuCoreR when Cargo
# is available. The GL effect presenter remains available as a fallback.
add_subdirectory(third_party/librashader)
if(TARGET librashader)
    target_link_libraries(emucoreh_jni PRIVATE librashader)
    target_compile_definitions(emucoreh_jni PRIVATE EMUCOREA_HAVE_LIBRASHADER=1)
endif()

target_include_directories(emucoreh_jni PRIVATE
    "${LIBRETRO_COMMON_DIR}"
    "${RCHEEVOS_DIR}/include"
    "${RCHEEVOS_DIR}/src"
)

find_library(EGL_LIBRARY EGL)
find_library(GLES_LIBRARY GLESv3)
find_library(AAUDIO_LIBRARY aaudio)
find_library(VULKAN_LIBRARY vulkan)

target_link_libraries(emucoreh_jni PRIVATE
    rcheevos_frontend
    android
    log
    dl
    ${EGL_LIBRARY}
    ${GLES_LIBRARY}
    ${AAUDIO_LIBRARY}
    ${VULKAN_LIBRARY}
)

target_compile_options(emucoreh_jni PRIVATE
    -Wall -Wextra
    $<$<NOT:$<CONFIG:Debug>>:-O3>
)

set_target_properties(emucoreh_jni PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
    OUTPUT_NAME "emucoreh_jni"
)
