cmake_minimum_required(VERSION 3.4.1)

project(cube)

find_package(games-frame-pacing REQUIRED CONFIG)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -std=c99  -Werror -DVK_USE_PLATFORM_ANDROID_KHR")

# Build Util Lib
set(UTILS_NAME  vsamputils)
set(GLSLANG_SPIRV_INCLUDE_DIR ${SHADERC_SRC}/third_party/glslang)
get_filename_component(PROJECT_SOURCE_DIR
                       "${CMAKE_SOURCE_DIR}/../../../.."
                       ABSOLUTE)

# Import shaderc lib in NDK
add_library(shaderc_lib STATIC IMPORTED)
set_target_properties(shaderc_lib PROPERTIES IMPORTED_LOCATION
                         ${SHADERC_SRC}/libs/${ANDROID_STL}/${ANDROID_ABI}/libshaderc.a)

find_program(GLSC names glslc HINTS "${ANDROID_NDK}/shader-tools/*")

foreach(shader vert frag)
    add_custom_command(
            COMMENT "Compiling ${shader}"
            OUTPUT cube.${shader}.inc
            COMMAND ${GLSC} -mfmt=num src/main/cpp/cube.${shader} -o src/main/cpp/include/cube.${shader}.inc
            MAIN_DEPENDENCY src/main/cpp/include/cube.${shader}.inc
            WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
    )
endforeach()

include_directories( src/main/cpp )

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -std=c99  -Werror -DVK_USE_PLATFORM_ANDROID_KHR")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11  -Werror -DVK_USE_PLATFORM_ANDROID_KHR")

add_library( ${CMAKE_PROJECT_NAME}

             SHARED

             cube.vert.inc
             cube.frag.inc
             src/main/cpp/cube.c
             src/main/cpp/native-lib.c
             src/main/cpp/common/vulkan_wrapper.cpp
        )

target_include_directories(${CMAKE_PROJECT_NAME}

                           PRIVATE

                           ${SHADERC_SRC}/third_party/spirv-tools/include
                           ${SHADERC_SRC}/third_party/spirv-tools/include/spirv-tools
                           src/main/cpp/include
                           src/main/cpp/common)


target_link_libraries( ${CMAKE_PROJECT_NAME}
                       games-frame-pacing::swappy_static
                       android
                       log
                       )
