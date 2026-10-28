cmake_minimum_required(VERSION 3.22)
project(beetle_saturn_libretro LANGUAGES C CXX)
if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
  message(FATAL_ERROR "Beetle Saturn currently supports Android arm64-v8a only")
endif()
include(sources.cmake)
add_library(saturn_libretro SHARED ${BEETLE_SATURN_SOURCES})
set_target_properties(saturn_libretro PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED YES
  CXX_STANDARD 11 CXX_STANDARD_REQUIRED YES PREFIX "lib" OUTPUT_NAME "saturn_libretro")
target_include_directories(saturn_libretro PRIVATE
  . mednafen mednafen/include mednafen/hw_cpu mednafen/ss mednafen/cdrom
  libretro-common/include
  deps/libchdr/include deps/lzma-19.00/include deps/zlib-1.2.11)
target_compile_definitions(saturn_libretro PRIVATE
  NDEBUG
  $<$<CONFIG:Debug>:SATURN_ENABLE_DIAGNOSTICS=1>
  __LIBRETRO__ _LOW_ACCURACY_ INLINE=inline WANT_SATURN_EMU
  PSS_STYLE=1 NEED_CD=1 NEED_BPP=32 WANT_32BPP NEED_DEINTERLACER
  WANT_THREADING HAVE_THREADS NEED_TREMOR FRONTEND_SUPPORTS_RGB565
  HAVE_CHD _7ZIP_ST SIZEOF_DOUBLE=8 SIZEOF_VOID_P=8
  PACKAGE="mednafen" STDC_HEADERS __STDC_LIMIT_MACROS __STDC_CONSTANT_MACROS
  MPC_FIXED_POINT HAVE_UNISTD_H)
# The SH2 interpreter and VDP/SCSP rasterizers are the hot path on Android.
# Keep frame pointers and stack canaries out of them: the emulator has no
# untrusted native input, and the canary setup/teardown shows up in every
# emulated instruction dispatch. -fno-unwind-tables also keeps the generated
# interpreter small enough to stay friendly to the phone's instruction cache.
# Do not tune for a fixed Cortex-A76: the current Snapdragon and Dimensity
# cores use different out-of-order pipelines and the scheduling hint only
# pessimizes them.
target_compile_options(saturn_libretro PRIVATE
  -O3 -flto=thin
  -fomit-frame-pointer -fno-stack-protector
  -fno-unwind-tables -fno-asynchronous-unwind-tables
  -fno-semantic-interposition -fno-math-errno -fno-trapping-math
  -fvisibility=hidden -fno-strict-aliasing
  -Wno-sign-compare -Wno-unused-variable -Wno-unused-function -Wno-uninitialized
  -Wno-strict-aliasing)
target_link_options(saturn_libretro PRIVATE -flto=thin -Wl,--icf=safe
  "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384"
  "-Wl,--no-undefined" "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/link.T")
target_link_libraries(saturn_libretro PRIVATE log android m)
