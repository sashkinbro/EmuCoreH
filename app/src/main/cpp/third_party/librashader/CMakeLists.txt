# librashader delivers RetroArch (.slangp) shader chains for the frontend
# post-process path.
#
# librashader is a RUST project, so it is fetched at configure time at a pinned
# commit and cargo-built for the Android ABI being built. Nothing lands in git.
# Its C-ABI crate is a leaf crate, so it must be built from its own manifest.
#
# RUST IS OPTIONAL: without cargo the target is not created and the frontend
# falls back to its built-in effect approximation.

set(LIBRASHADER_PIN "87e8a97b50516d997defeaa168173dcd185d4022"
    CACHE STRING "Pinned librashader commit.")

find_program(CARGO_EXECUTABLE cargo HINTS "$ENV{USERPROFILE}/.cargo/bin" "$ENV{HOME}/.cargo/bin" "$ENV{CARGO_HOME}/bin")
if(NOT CARGO_EXECUTABLE)
    message(STATUS "librashader: cargo not found -> RetroArch shader chains DISABLED.")
    set(EMUCOREA_HAVE_LIBRASHADER OFF PARENT_SCOPE)
    return()
endif()

# Windows installations without MSVC Build Tools can use Rust's GNU host
# toolchain. Cargo needs dlltool on PATH for host-side build scripts.
set(_lrs_host_env)
set(_lrs_path_modify)
if(CMAKE_HOST_WIN32)
    find_program(_lrs_host_link link)
    find_program(_lrs_host_dlltool dlltool HINTS "C:/msys64/ucrt64/bin" "C:/msys64/mingw64/bin")
    if(NOT _lrs_host_link AND _lrs_host_dlltool AND
       EXISTS "$ENV{USERPROFILE}/.rustup/toolchains/stable-x86_64-pc-windows-gnu")
        get_filename_component(_lrs_dlltool_dir "${_lrs_host_dlltool}" DIRECTORY)
        set(_lrs_host_env "RUSTUP_TOOLCHAIN=stable-x86_64-pc-windows-gnu")
        set(_lrs_path_modify --modify "PATH=path_list_prepend:${_lrs_dlltool_dir}")
    endif()
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(_lrs_rust_target "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(_lrs_rust_target "armv7-linux-androideabi")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(_lrs_rust_target "x86_64-linux-android")
elseif(ANDROID_ABI STREQUAL "x86")
    set(_lrs_rust_target "i686-linux-android")
else()
    message(STATUS "librashader: unmapped ANDROID_ABI '${ANDROID_ABI}' -> DISABLED.")
    set(EMUCOREA_HAVE_LIBRASHADER OFF PARENT_SCOPE)
    return()
endif()

if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
    set(_lrs_host_tag "darwin-x86_64")
elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
    set(_lrs_host_tag "linux-x86_64")
else()
    set(_lrs_host_tag "windows-x86_64")
endif()
set(_lrs_ndk "${CMAKE_ANDROID_NDK}")
if(NOT _lrs_ndk)
    set(_lrs_ndk "$ENV{ANDROID_NDK_HOME}")
endif()
set(_lrs_tc "${_lrs_ndk}/toolchains/llvm/prebuilt/${_lrs_host_tag}")

set(_lrs_api "${ANDROID_PLATFORM}")
string(REGEX REPLACE "^android-" "" _lrs_api "${_lrs_api}")
if(NOT _lrs_api)
    set(_lrs_api 26)
endif()

set(_lrs_cc "${_lrs_tc}/bin/${_lrs_rust_target}${_lrs_api}-clang")
if(_lrs_rust_target STREQUAL "armv7-linux-androideabi")
    set(_lrs_cc "${_lrs_tc}/bin/armv7a-linux-androideabi${_lrs_api}-clang")
endif()
set(_lrs_cxx "${_lrs_cc}++")

if(CMAKE_HOST_WIN32)
    if(EXISTS "${_lrs_cc}.cmd")
        set(_lrs_cc "${_lrs_cc}.cmd")
    endif()
    if(EXISTS "${_lrs_cxx}.cmd")
        set(_lrs_cxx "${_lrs_cxx}.cmd")
    endif()
endif()

if(NOT EXISTS "${_lrs_cc}")
    message(STATUS "librashader: no NDK clang at ${_lrs_cc} -> DISABLED.")
    set(EMUCOREA_HAVE_LIBRASHADER OFF PARENT_SCOPE)
    return()
endif()

string(TOUPPER "${_lrs_rust_target}" _lrs_target_upper)
string(REPLACE "-" "_" _lrs_target_upper "${_lrs_target_upper}")
string(REPLACE "-" "_" _lrs_target_under "${_lrs_rust_target}")

include(FetchContent)
FetchContent_Declare(librashader_src
    GIT_REPOSITORY https://github.com/SnowflakePowered/librashader.git
    GIT_TAG        ${LIBRASHADER_PIN}
    SOURCE_SUBDIR  cmake-does-not-exist
)
FetchContent_MakeAvailable(librashader_src)

# The pinned librashader GL backend always binds mipmapped samplers, so any
# texture with a single mip level is mipmap incomplete and strict GLES drivers
# (Adreno) sample it as black - the whole chain then outputs nothing. Replace
# the two GL runtime files with the patched copies in ./patched that bind a
# non-mipmapped sampler for level-less textures. Keep them in sync with the pin.
set(_lrs_gl_src "${librashader_src_SOURCE_DIR}/librashader-runtime-gl/src")
execute_process(COMMAND ${CMAKE_COMMAND} -E copy_if_different
    "${CMAKE_CURRENT_SOURCE_DIR}/patched/samplers.rs" "${_lrs_gl_src}/samplers.rs")
execute_process(COMMAND ${CMAKE_COMMAND} -E copy_if_different
    "${CMAKE_CURRENT_SOURCE_DIR}/patched/gl3_texture_bind.rs"
    "${_lrs_gl_src}/gl/gl3/texture_bind.rs")
set(_lrs_out_dir "${CMAKE_CURRENT_BINARY_DIR}/cargo")
set(_lrs_lib "${_lrs_out_dir}/${_lrs_rust_target}/release/liblibrashader_capi.so")

add_custom_command(
    OUTPUT "${_lrs_lib}"
    COMMAND ${CMAKE_COMMAND} -E env ${_lrs_path_modify}
        ${_lrs_host_env}
        "CARGO_TARGET_${_lrs_target_upper}_LINKER=${_lrs_cc}"
        "CC_${_lrs_target_under}=${_lrs_cc}"
        "CXX_${_lrs_target_under}=${_lrs_cxx}"
        "AR_${_lrs_target_under}=${_lrs_tc}/bin/llvm-ar"
        "RANLIB_${_lrs_target_under}=${_lrs_tc}/bin/llvm-ranlib"
        "RUSTFLAGS=-C link-arg=-Wl,-soname,liblibrashader_capi.so"
        ${CARGO_EXECUTABLE} build --release
            --manifest-path "${librashader_src_SOURCE_DIR}/librashader-capi/Cargo.toml"
            --target ${_lrs_rust_target}
            --target-dir "${_lrs_out_dir}"
            --no-default-features
            --features runtime-vulkan,runtime-opengl
    WORKING_DIRECTORY "${librashader_src_SOURCE_DIR}"
    COMMENT "librashader: cargo build (${_lrs_rust_target}, vulkan+opengl)"
    DEPENDS
        "${CMAKE_CURRENT_SOURCE_DIR}/patched/samplers.rs"
        "${CMAKE_CURRENT_SOURCE_DIR}/patched/gl3_texture_bind.rs"
    VERBATIM
)
add_custom_target(librashader_cargo DEPENDS "${_lrs_lib}")

# AGP only packages .so files CMake emits into the per-ABI library output dir.
if(CMAKE_LIBRARY_OUTPUT_DIRECTORY)
    add_custom_command(TARGET librashader_cargo POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
        COMMAND ${CMAKE_COMMAND} -E copy_if_different "${_lrs_lib}" "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/"
        COMMENT "librashader: staging liblibrashader_capi.so for packaging"
        VERBATIM
    )
endif()

add_library(librashader SHARED IMPORTED GLOBAL)
set_target_properties(librashader PROPERTIES IMPORTED_LOCATION "${_lrs_lib}")
target_include_directories(librashader INTERFACE "${librashader_src_SOURCE_DIR}/include")
target_compile_definitions(librashader INTERFACE LIBRA_RUNTIME_VULKAN=1 LIBRA_RUNTIME_OPENGL=1)
add_dependencies(librashader librashader_cargo)

set(EMUCOREA_HAVE_LIBRASHADER ON PARENT_SCOPE)
message(STATUS "librashader: ENABLED (${_lrs_rust_target}, api ${_lrs_api}, vulkan+opengl)")
