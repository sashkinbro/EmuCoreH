# SPDX-License-Identifier: GPL-3.0-only
cmake_minimum_required(VERSION 3.22)
project(saturn_libretro LANGUAGES C CXX ASM)
if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
  message(FATAL_ERROR "Saturn currently supports Android arm64-v8a only")
endif()
include(sources.cmake)
add_library(saturn_libretro SHARED ${SATURN_SOURCES} disc_probe.c disc_control.c state_validation.c)
option(SATURN_ENABLE_SCSP_DIAGNOSTICS "Compile opt-in SCSP worker phase telemetry for native QA" OFF)
if(SATURN_ENABLE_SCSP_DIAGNOSTICS AND CMAKE_BUILD_TYPE STREQUAL "Debug")
  target_compile_definitions(saturn_libretro PRIVATE SATURN_ENABLE_SCSP_DIAGNOSTICS=1)
endif()
option(SATURN_SYNCHRONOUS_SCSP "Run SCSP on the emulation thread for deterministic sound state" ON)
if(SATURN_SYNCHRONOUS_SCSP)
  target_compile_definitions(saturn_libretro PRIVATE SATURN_SYNCHRONOUS_SCSP=1)
endif()
# Diagnostic code is available only in Debug, including explicit QA opt-ins.
target_compile_definitions(saturn_libretro PRIVATE
  "$<$<NOT:$<CONFIG:Debug>>:SATURN_RELEASE_SILENT_DIAGNOSTICS=1;DONT_PROFILE=1>")
set_target_properties(saturn_libretro PROPERTIES C_STANDARD 11 CXX_STANDARD 11 CXX_STANDARD_REQUIRED YES)
target_include_directories(saturn_libretro PRIVATE src src/libretro src/libretro/libretro-common/include src/musashi
  src/libchdr/include src/libchdr/deps/lzma-19.00/include src/libchdr/deps/flac-1.3.3/include
  src/libchdr/deps/flac-1.3.3/src/include src/libchdr/deps/zlib-1.2.11)
target_compile_definitions(saturn_libretro PRIVATE _GNU_SOURCE __LIBRETRO__ HAVE_LIBGL VERSION="3.4.2" GIT_VERSION="09ed8e5"
  HAVE_LROUND _7ZIP_ST FLAC__HAS_OGG=0 FLAC__NO_DLL NO_CLI HAVE_BUILTIN_BSWAP16=1 HAVE_BUILTIN_BSWAP32=1
  HAVE_C99_VARIADIC_MACROS=1 HAVE_FLOORF=1 HAVE_GETTIMEOFDAY=1 HAVE_STDINT_H=1 HAVE_SYS_TIME_H=1
  IMPROVED_SAVESTATES PACKAGE_VERSION="1.3.3" ARCH_IS_LINUX=1 DYNAREC_DEVMIYAX=1 HAVE_GLSYM_PRIVATE
  HAVE_SYS_PARAM_H HAVE_MUSASHI=1 _OGLES3_ HAVE_OPENGLES HAVE_OPENGLES3 ANDROID AARCH64)
target_compile_options(saturn_libretro PRIVATE -O3 -fvisibility=hidden -ffast-math
  "$<$<COMPILE_LANGUAGE:C>:-Wno-error=implicit-function-declaration>"
  "$<$<COMPILE_LANGUAGE:C>:-Wno-error=incompatible-pointer-types>")
target_link_options(saturn_libretro PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384"
  "-Wl,--no-undefined" "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/src/libretro/link.T")
target_link_libraries(saturn_libretro PRIVATE GLESv3 log android)
