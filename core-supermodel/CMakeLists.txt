# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22)
project(supermodel_libretro LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
  message(FATAL_ERROR "This port currently supports Android arm64-v8a only")
endif()
file(GLOB_RECURSE CORE_SOURCES CONFIGURE_DEPENDS "Src/*.cpp" "Src/*.c")
list(FILTER CORE_SOURCES EXCLUDE REGEX "/Src/(OSD|Graphics|Network)/|/Src/CPU/68K/Musashi/(m68k_in|m68kmake)\\.c$|/Src/CPU/68K/Turbo68K/|/Src/CPU/PowerPC/(ppc603|ppc_ops)\\.c$|/Src/CPU/PowerPC/PPCDisasm\\.cpp$|/Src/Model3/53C810Disasm\\.cpp$|/Src/Sound/SCSPLFO\\.cpp$|/Src/Pkgs/(GL/|glew\\.c$)|/Src/Util/Test_")
set(NEW3D_NAMES Mat4 GLSLShader Model New3D PolyHeader R3DFloat R3DFrameBuffers R3DShader R3DScrollFog Texture TextureSheet VBO Vec)
foreach(name IN LISTS NEW3D_NAMES)
  list(APPEND CORE_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/Src/Graphics/New3D/${name}.cpp")
endforeach()
add_library(supermodel_libretro SHARED ${CORE_SOURCES}
  Src/Network/StandaloneNetBoard.cpp
  libretro/libretro.cpp libretro/platform.cpp libretro/gles_presenter.cpp
  libretro/render2d_android.cpp
  libretro/generated/m68kops.c libretro/generated/m68kopac.c libretro/generated/m68kopdm.c libretro/generated/m68kopnz.c)
target_include_directories(supermodel_libretro PRIVATE Src Src/CPU/68K/Musashi libretro libretro/generated)
# Model 3 games with a network board (Daytona USA 2, Scud Race, Sega Rally 2,
# ...) refuse to boot when the board is absent. Android uses the standalone
# one-cabinet implementation: no SDL_net, real socket or second instance.
target_compile_definitions(supermodel_libretro PRIVATE ANDROID __ANDROID__ EMUCOREH_LIBRETRO=1 NET_BOARD=1 NET_BOARD_STANDALONE=1)
# Release contains only terminal adapter error reporting, not guest diagnostics.
target_compile_definitions(supermodel_libretro PRIVATE
  "$<$<CONFIG:Release,RelWithDebInfo,MinSizeRel>:MODEL3_RELEASE_SILENT_LOGS=1>")
target_compile_options(supermodel_libretro PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden -O3)
target_link_options(supermodel_libretro PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384" "-Wl,--no-undefined" "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/libretro/exports.map")
target_link_libraries(supermodel_libretro PRIVATE log android z EGL GLESv3)
set_target_properties(supermodel_libretro PROPERTIES OUTPUT_NAME supermodel_libretro)
option(MODEL3_BUILD_LIFECYCLE_TESTS "Build Android libretro ABI/lifecycle test executables" OFF)
if(MODEL3_BUILD_LIFECYCLE_TESTS)
  add_executable(model3_graphics_lifecycle libretro/tests/graphics_lifecycle.cpp Src/Graphics/New3D/R3DShader.cpp Src/Util/NewConfig.cpp Src/Util/Format.cpp)
  target_include_directories(model3_graphics_lifecycle PRIVATE Src libretro)
  target_compile_definitions(model3_graphics_lifecycle PRIVATE __ANDROID__)
  target_link_libraries(model3_graphics_lifecycle PRIVATE EGL GLESv3)
  target_link_options(model3_graphics_lifecycle PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
  add_executable(model3_resolution_lifecycle libretro/tests/resolution_lifecycle.cpp)
  target_include_directories(model3_resolution_lifecycle PRIVATE libretro)
  target_link_libraries(model3_resolution_lifecycle PRIVATE dl EGL GLESv3)
  target_link_options(model3_resolution_lifecycle PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
  add_executable(model3_no_content_lifecycle libretro/tests/no_content_lifecycle.cpp)
  target_include_directories(model3_no_content_lifecycle PRIVATE libretro)
  target_link_libraries(model3_no_content_lifecycle PRIVATE dl)
  target_link_options(model3_no_content_lifecycle PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
  add_executable(model3_runtime_failure_lifecycle libretro/tests/runtime_failure_lifecycle.cpp)
  target_include_directories(model3_runtime_failure_lifecycle PRIVATE libretro)
  target_link_libraries(model3_runtime_failure_lifecycle PRIVATE dl EGL GLESv3)
  target_link_options(model3_runtime_failure_lifecycle PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
endif()
