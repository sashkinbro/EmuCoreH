# SPDX-License-Identifier: GPL-3.0-only
# Host build of the Beetle Saturn core plus the host_sat harness (MSYS2 ucrt64 clang).
# SATURN_DIR selects the core tree, so a baseline export can be built side by side.
cmake_minimum_required(VERSION 3.22)
project(host_sat LANGUAGES C CXX)
set(SATURN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../core-saturn" CACHE PATH "core-saturn tree")
set(HOST_SAT_LTO ON CACHE BOOL "ThinLTO like the Android build")
include(${SATURN_DIR}/sources.cmake)
list(TRANSFORM BEETLE_SATURN_SOURCES PREPEND "${SATURN_DIR}/")
add_executable(host_sat ${BEETLE_SATURN_SOURCES} host_sat.cpp)
set_target_properties(host_sat PROPERTIES C_STANDARD 11 CXX_STANDARD 17)
target_include_directories(host_sat PRIVATE ${SATURN_DIR} ${SATURN_DIR}/mednafen ${SATURN_DIR}/mednafen/include
  ${SATURN_DIR}/mednafen/hw_cpu ${SATURN_DIR}/mednafen/ss ${SATURN_DIR}/mednafen/cdrom
  ${SATURN_DIR}/libretro-common/include ${SATURN_DIR}/deps/libchdr/include
  ${SATURN_DIR}/deps/lzma-19.00/include ${SATURN_DIR}/deps/zlib-1.2.11)
# Same definitions as core-saturn/CMakeLists.txt (Android).
target_compile_definitions(host_sat PRIVATE NDEBUG __LIBRETRO__ _LOW_ACCURACY_ INLINE=inline WANT_SATURN_EMU
  PSS_STYLE=1 NEED_CD=1 NEED_BPP=32 WANT_32BPP NEED_DEINTERLACER WANT_THREADING HAVE_THREADS NEED_TREMOR
  FRONTEND_SUPPORTS_RGB565 HAVE_CHD _7ZIP_ST SIZEOF_DOUBLE=8 SIZEOF_VOID_P=8 PACKAGE="mednafen" STDC_HEADERS
  __STDC_LIMIT_MACROS __STDC_CONSTANT_MACROS MPC_FIXED_POINT HAVE_UNISTD_H)
target_compile_options(host_sat PRIVATE -O3 -g -fomit-frame-pointer -fno-stack-protector -fno-math-errno
  -fno-trapping-math -fno-strict-aliasing -w -include stdint.h)
if(HOST_SAT_LTO)
  target_compile_options(host_sat PRIVATE -flto=thin)
  target_link_options(host_sat PRIVATE -flto=thin -fuse-ld=lld)
endif()
target_link_libraries(host_sat PRIVATE winmm)
