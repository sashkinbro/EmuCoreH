# SPDX-License-Identifier: GPL-3.0-or-later
# Host build of the Model 3 core for tools/oracle/host_m3.sh. Mirrors core-supermodel/CMakeLists.txt
# (same source list and defines, __ANDROID__ code paths) minus GLES/Vulkan presentation.
# M3_CORE_DIR selects the core tree (default: this checkout), so a baseline can be built from
# a `git archive` copy. M3_GLES_INCLUDE must contain GLES2/, GLES3/ and KHR/ headers (NDK sysroot).
cmake_minimum_required(VERSION 3.22)
project(m3_host_oracle LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(M3_CORE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../core-supermodel" CACHE PATH "core-supermodel tree")
set(M3_GLES_INCLUDE "" CACHE PATH "directory with GLES2/GLES3/KHR headers")
option(M3_LTO "Thin LTO like the Android build" ON)
option(M3_PROFILE_SYMBOLS "Keep debug line info for the sampling profiler" OFF)

file(GLOB_RECURSE CORE_SOURCES CONFIGURE_DEPENDS "${M3_CORE_DIR}/Src/*.cpp" "${M3_CORE_DIR}/Src/*.c")
list(FILTER CORE_SOURCES EXCLUDE REGEX "/Src/(OSD|Graphics|Network)/|/Src/CPU/68K/Musashi/(m68k_in|m68kmake)\\.c$|/Src/CPU/68K/Turbo68K/|/Src/CPU/PowerPC/(ppc603|ppc_ops)\\.c$|/Src/CPU/PowerPC/PPCDisasm\\.cpp$|/Src/Model3/53C810Disasm\\.cpp$|/Src/Sound/SCSPLFO\\.cpp$|/Src/Pkgs/(GL/|glew\\.c$)|/Src/Util/Test_")
foreach(name Mat4 GLSLShader Model New3D PolyHeader R3DFloat R3DFrameBuffers R3DShader R3DScrollFog Texture TextureSheet VBO Vec)
  list(APPEND CORE_SOURCES "${M3_CORE_DIR}/Src/Graphics/New3D/${name}.cpp")
endforeach()
add_executable(m3-host-oracle ${CORE_SOURCES}
  "${M3_CORE_DIR}/Src/Network/StandaloneNetBoard.cpp"
  "${M3_CORE_DIR}/libretro/platform.cpp"
  "${M3_CORE_DIR}/libretro/render2d_android.cpp"
  "${M3_CORE_DIR}/libretro/generated/m68kops.c" "${M3_CORE_DIR}/libretro/generated/m68kopac.c"
  "${M3_CORE_DIR}/libretro/generated/m68kopdm.c" "${M3_CORE_DIR}/libretro/generated/m68kopnz.c"
  m3_host_oracle.cpp gl_stubs.c)
target_include_directories(m3-host-oracle BEFORE PRIVATE "${M3_GLES_INCLUDE}")
target_include_directories(m3-host-oracle PRIVATE "${M3_CORE_DIR}/Src" "${M3_CORE_DIR}/Src/CPU/68K/Musashi"
  "${M3_CORE_DIR}/libretro" "${M3_CORE_DIR}/libretro/generated")
target_compile_definitions(m3-host-oracle PRIVATE ANDROID __ANDROID__ EMUCOREH_LIBRETRO=1 NET_BOARD=1
  NET_BOARD_STANDALONE=1 MODEL3_RELEASE_SILENT_LOGS=1 GL_APICALL= GL_APIENTRY= NOMINMAX WIN32_LEAN_AND_MEAN)
target_compile_options(m3-host-oracle PRIVATE -O3 -Wno-everything "$<$<COMPILE_LANGUAGE:CXX>:SHELL:-include cstdint>")
if(M3_LTO)
  target_compile_options(m3-host-oracle PRIVATE -flto=thin)
  target_link_options(m3-host-oracle PRIVATE -flto=thin -fuse-ld=lld)
endif()
if(M3_PROFILE_SYMBOLS)
  target_compile_options(m3-host-oracle PRIVATE -gline-tables-only)
  target_link_options(m3-host-oracle PRIVATE -g)
endif()
target_link_libraries(m3-host-oracle PRIVATE z)
