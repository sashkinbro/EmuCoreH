cmake_minimum_required(VERSION 3.22)
project(emucoreh_dcui CXX)

# Google Play 16 KiB page alignment support.
add_link_options("-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")

option(DCUI_ENABLE_LTO "Enable ThinLTO for the native Dreamcast BIOS UI renderer" ON)

add_library(emucoreh_dcui SHARED
    dc_jni.cpp
    dc_renderer.cpp
    dc_screens.cpp
    dc_input.cpp
)

target_compile_features(emucoreh_dcui PRIVATE cxx_std_17)
target_compile_options(emucoreh_dcui PRIVATE
    -Wall -Wextra -Wno-unused-parameter
    -O3
    -ffunction-sections -fdata-sections -fomit-frame-pointer
    -fno-exceptions -fno-rtti
)

if(DCUI_ENABLE_LTO AND CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(emucoreh_dcui PRIVATE "-flto=thin")
    target_link_options(emucoreh_dcui PRIVATE "-flto=thin" "-Wl,--gc-sections" "-Wl,--icf=safe")
endif()

set_target_properties(emucoreh_dcui PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

find_library(android-lib android)
find_library(log-lib log)
find_library(GLESv3-lib GLESv3)
target_link_libraries(emucoreh_dcui PRIVATE ${android-lib} ${log-lib} ${GLESv3-lib})
