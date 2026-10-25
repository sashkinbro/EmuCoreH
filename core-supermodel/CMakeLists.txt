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
option(MODEL3_ENABLE_EXPERIMENTAL_VULKAN "Build the experimental Real3D Vulkan consumer" OFF)
option(MODEL3_TEST_ACCURATE_GLES "Use upstream accurate GLES passes for renderer comparison probes" OFF)
if(MODEL3_TEST_ACCURATE_GLES)
  target_compile_definitions(supermodel_libretro PRIVATE MODEL3_TEST_ACCURATE_GLES=1)
endif()
option(MODEL3_BUILD_LIFECYCLE_TESTS "Build Android libretro ABI/lifecycle test executables" OFF)
if(MODEL3_ENABLE_EXPERIMENTAL_VULKAN OR MODEL3_BUILD_LIFECYCLE_TESTS)
  find_package(Python3 REQUIRED COMPONENTS Interpreter)
  find_program(MODEL3_GLSLC glslc HINTS "${CMAKE_ANDROID_NDK}/shader-tools/windows-x86_64" "${CMAKE_ANDROID_NDK}/shader-tools/linux-x86_64" "${CMAKE_ANDROID_NDK}/shader-tools/darwin-x86_64" REQUIRED)
  set(MODEL3_VULKAN_SHADER_HEADER "${CMAKE_CURRENT_BINARY_DIR}/generated/VulkanShaders.h")
  add_custom_command(OUTPUT "${MODEL3_VULKAN_SHADER_HEADER}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${CMAKE_CURRENT_BINARY_DIR}/generated"
    COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/Src/Graphics/Vulkan/compile_shaders.py"
      "${MODEL3_GLSLC}" "${CMAKE_CURRENT_BINARY_DIR}/generated" "${MODEL3_VULKAN_SHADER_HEADER}"
    DEPENDS Src/Graphics/Vulkan/compile_shaders.py Src/Graphics/New3D/R3DShaderTriangles.h
    VERBATIM)
endif()
if(MODEL3_ENABLE_EXPERIMENTAL_VULKAN)
  target_sources(supermodel_libretro PRIVATE Src/Graphics/Vulkan/VulkanScene.cpp "${MODEL3_VULKAN_SHADER_HEADER}")
  target_include_directories(supermodel_libretro PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/generated")
  target_compile_definitions(supermodel_libretro PRIVATE MODEL3_ENABLE_EXPERIMENTAL_VULKAN=1)
  target_link_libraries(supermodel_libretro PRIVATE vulkan)
endif()
set_target_properties(supermodel_libretro PROPERTIES OUTPUT_NAME supermodel_libretro)
if(MODEL3_BUILD_LIFECYCLE_TESTS)
  target_compile_definitions(supermodel_libretro PRIVATE MODEL3_RENDER_SNAPSHOT=1)
  add_library(model3_vulkan_validation SHARED libretro/tests/vulkan_activity.cpp libretro/tests/vulkan_core_activity.cpp
    Src/Graphics/Vulkan/VulkanScene.cpp Src/Graphics/New3D/Mat4.cpp "${MODEL3_VULKAN_SHADER_HEADER}")
  target_include_directories(model3_vulkan_validation PRIVATE Src libretro "${CMAKE_CURRENT_BINARY_DIR}/generated")
  target_link_libraries(model3_vulkan_validation PRIVATE vulkan android log)
  target_link_options(model3_vulkan_validation PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
  add_executable(model3_gles_state_compare libretro/tests/gles_state_compare.cpp)
  target_include_directories(model3_gles_state_compare PRIVATE libretro)
  target_link_libraries(model3_gles_state_compare PRIVATE EGL GLESv3 dl)
  target_link_options(model3_gles_state_compare PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
  add_executable(model3_vulkan_core_lifecycle libretro/tests/vulkan_core_lifecycle.cpp)
  target_include_directories(model3_vulkan_core_lifecycle PRIVATE libretro)
  target_link_libraries(model3_vulkan_core_lifecycle PRIVATE vulkan dl)
  target_link_options(model3_vulkan_core_lifecycle PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
  add_executable(model3_vulkan_scene_lifecycle libretro/tests/vulkan_scene_lifecycle.cpp
    Src/Graphics/Vulkan/VulkanScene.cpp Src/Graphics/New3D/Mat4.cpp "${MODEL3_VULKAN_SHADER_HEADER}")
  target_include_directories(model3_vulkan_scene_lifecycle PRIVATE Src libretro "${CMAKE_CURRENT_BINARY_DIR}/generated")
  target_compile_definitions(model3_vulkan_scene_lifecycle PRIVATE MODEL3_TEST_HOST_VISIBILITY=1 MODEL3_TEST_RESOURCE_FAILURES=1)
  target_link_libraries(model3_vulkan_scene_lifecycle PRIVATE vulkan)
  target_link_options(model3_vulkan_scene_lifecycle PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384"
    "-Wl,--wrap=vkBeginCommandBuffer" "-Wl,--wrap=vkCmdCopyImageToBuffer"
    "-Wl,--wrap=vkCmdPipelineBarrier" "-Wl,--wrap=vkQueueSubmit"
    "-Wl,--wrap=vkCreateImage" "-Wl,--wrap=vkDestroyImage" "-Wl,--wrap=vkAllocateMemory" "-Wl,--wrap=vkFreeMemory"
    "-Wl,--wrap=vkCreateImageView" "-Wl,--wrap=vkCreateShaderModule" "-Wl,--wrap=vkDestroyShaderModule"
    "-Wl,--wrap=vkCreateBuffer" "-Wl,--wrap=vkDestroyBuffer" "-Wl,--wrap=vkMapMemory")
  set(SCENE_TEST_SOURCES libretro/tests/scene_consumer_lifecycle.cpp Src/Util/NewConfig.cpp Src/Util/Format.cpp)
  foreach(name IN LISTS NEW3D_NAMES)
    list(APPEND SCENE_TEST_SOURCES "Src/Graphics/New3D/${name}.cpp")
  endforeach()
  add_executable(model3_scene_consumer_lifecycle ${SCENE_TEST_SOURCES})
  target_include_directories(model3_scene_consumer_lifecycle PRIVATE Src libretro)
  target_compile_definitions(model3_scene_consumer_lifecycle PRIVATE __ANDROID__)
  target_link_libraries(model3_scene_consumer_lifecycle PRIVATE GLESv3)
  target_link_options(model3_scene_consumer_lifecycle PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384"
    "-Wl,--wrap=glGenBuffers" "-Wl,--wrap=glCreateShader" "-Wl,--wrap=glUseProgram"
    "-Wl,--wrap=glViewport" "-Wl,--wrap=glDrawArrays" "-Wl,--wrap=glDeleteBuffers")
  add_executable(model3_graphics_lifecycle libretro/tests/graphics_lifecycle.cpp Src/Graphics/New3D/R3DShader.cpp
    Src/Graphics/New3D/R3DFrameBuffers.cpp Src/Graphics/New3D/GLSLShader.cpp Src/Graphics/New3D/VBO.cpp
    Src/Graphics/New3D/Mat4.cpp Src/Util/NewConfig.cpp Src/Util/Format.cpp)
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
