cmake_minimum_required(VERSION 3.4.1)

set( EXE_EXTENSION "")
set( PLUGIN_EXTENSION "")
if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
  set( HOST_PLATFORM "mac")
elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
  set( HOST_PLATFORM "win")
  set( EXE_EXTENSION ".exe")
  set( PLUGIN_EXTENSION ".bat")
else()
  set( HOST_PLATFORM "linux-x86")
endif()
set( PROTOBUF_INSTALL_DIR "${CMAKE_CURRENT_LIST_DIR}/../../third_party/protoc-3.21.7/${HOST_PLATFORM}")

set( PROTOBUF_SRC_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../external/protobuf/src")
set(PROTOC_EXE ${PROTOBUF_INSTALL_DIR}/bin/protoc${EXE_EXTENSION})
set( PROTOBUF_INCLUDE_DIR ${PROTOBUF_SRC_DIR} )

set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++14 -Werror -Wthread-safety" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -D _LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS -Os -fPIC" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-rtti" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DGOOGLE_PROTOBUF_NO_RTTI -DHAVE_PTHREAD")
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections" )

function(set_link_options libname versionscript)
  if (${CMAKE_BUILD_TYPE} STREQUAL "Release")
  set_target_properties( ${libname} PROPERTIES LINK_FLAGS "-Wl,--version-script=${versionscript}")
  endif (${CMAKE_BUILD_TYPE} STREQUAL "Release")
endfunction()

function(extra_pb_link_options libname)
  set_link_options(${libname} "${CMAKE_CURRENT_LIST_DIR}/protobuf_version.script")
endfunction()

set(PROTO_GENS_DIR ${CMAKE_BINARY_DIR}/gens)
get_directory_property(hasParent PARENT_DIRECTORY)
if(hasParent)
  set(PROTO_GENS_DIR ${_PROTO_GENS_DIR} PARENT_SCOPE)
  set(PROTOC_EXE ${PROTOC_EXE} PARENT_SCOPE)
  set(PROTOBUF_SRC_DIR ${PROTOBUF_SRC_DIR} PARENT_SCOPE)
endif()
file(MAKE_DIRECTORY ${PROTO_GENS_DIR}/full)
file(MAKE_DIRECTORY ${PROTO_GENS_DIR}/lite)

function(protobuf_generate_base)
  if(NOT ARGN)
    message(SEND_ERROR "Error: PROTOBUF_GENERATE_CPP() called without any proto files")
    return()
  endif()

  set(_extension ${ARGV0})
  set(_out_opt ${ARGV1})
  set(_extra_opts ${ARGV2})
  set(_gens_subdir ${ARGV3})
  set(_opt_prefix ${ARGV4})
  set(_working_dir ${ARGV5})
  list(REMOVE_AT ARGN 0)
  list(REMOVE_AT ARGN 0)
  list(REMOVE_AT ARGN 0)
  list(REMOVE_AT ARGN 0)
  list(REMOVE_AT ARGN 0)
  list(REMOVE_AT ARGN 0)

  set(_protobuf_include_path -I .)
  set(_PROTO_GENS_DIR ${CMAKE_BINARY_DIR}/gens${_gens_subdir})
  message(STATUS "protobuf_generate_base._working_dir=${_working_dir}")
  message(STATUS "protobuf_generate_base._PROTO_GENS_DIR=${_PROTO_GENS_DIR}")
  message(STATUS "protobuf_generate_base._extension=${_extension}")
  message(STATUS "protobuf_generate_base.PROTOC_EXE=${PROTOC_EXE}")
  foreach(FIL ${ARGN})
    get_filename_component(ABS_FIL ${FIL} ABSOLUTE)
    get_filename_component(FIL_WE ${FIL} NAME_WE)
    file(RELATIVE_PATH REL_FIL ${_working_dir} ${ABS_FIL})
    get_filename_component(REL_DIR ${REL_FIL} DIRECTORY)
    set(RELFIL_WE "${REL_DIR}/${FIL_WE}")

    add_custom_command(
      OUTPUT "${_PROTO_GENS_DIR}${RELFIL_WE}.pb.${_extension}"
             "${_PROTO_GENS_DIR}${RELFIL_WE}.pb.h"
      COMMAND ${PROTOC_EXE}
      ARGS ${_out_opt}=${_opt_prefix}${_PROTO_GENS_DIR}
           ${_extra_opts}
           ${_protobuf_include_path}
           ${REL_FIL}
      WORKING_DIRECTORY ${_working_dir}
      MAIN_DEPENDENCY ${FIL}
      COMMENT "Running C++ protocol buffer compiler on ${FIL}"
      VERBATIM)

    message(STATUS "${PROTOC_EXE} will generate ${_PROTO_GENS_DIR}${RELFIL_WE}.pb.*")

    set_source_files_properties(
      "${_PROTO_GENS_DIR}${RELFIL_WE}.pb.${_extension}"
      "${_PROTO_GENS_DIR}${RELFIL_WE}.pb.h" PROPERTIES GENERATED TRUE)
  endforeach()
endfunction()


function(protobuf_generate_full_cpp)
  protobuf_generate_base("cc" "--cpp_out" "" "/full" "" ${ARGN})
endfunction()

function(protobuf_generate_lite_cpp)
  protobuf_generate_base("cc" "--cpp_out" "" "/lite" "lite:" ${ARGN})
endfunction()

set(GP_SRC_DIR ${PROTOBUF_SRC_DIR}/google/protobuf)

set(PROTOBUF_LITE_SRCS
    ${GP_SRC_DIR}/any_lite.cc
    ${GP_SRC_DIR}/arena.cc
    ${GP_SRC_DIR}/arenastring.cc
    ${GP_SRC_DIR}/arenaz_sampler.cc
    ${GP_SRC_DIR}/extension_set.cc
    ${GP_SRC_DIR}/generated_enum_util.cc
    ${GP_SRC_DIR}/generated_message_tctable_lite.cc
    ${GP_SRC_DIR}/generated_message_util.cc
    ${GP_SRC_DIR}/implicit_weak_message.cc
    ${GP_SRC_DIR}/inlined_string_field.cc
    ${GP_SRC_DIR}/io/coded_stream.cc
    ${GP_SRC_DIR}/io/io_win32.cc
    ${GP_SRC_DIR}/io/strtod.cc
    ${GP_SRC_DIR}/io/zero_copy_stream.cc
    ${GP_SRC_DIR}/io/zero_copy_stream_impl.cc
    ${GP_SRC_DIR}/io/zero_copy_stream_impl_lite.cc
    ${GP_SRC_DIR}/map.cc
    ${GP_SRC_DIR}/message_lite.cc
    ${GP_SRC_DIR}/parse_context.cc
    ${GP_SRC_DIR}/repeated_field.cc
    ${GP_SRC_DIR}/repeated_ptr_field.cc
    ${GP_SRC_DIR}/stubs/bytestream.cc
    ${GP_SRC_DIR}/stubs/common.cc
    ${GP_SRC_DIR}/stubs/int128.cc
    ${GP_SRC_DIR}/stubs/status.cc
    ${GP_SRC_DIR}/stubs/statusor.cc
    ${GP_SRC_DIR}/stubs/stringpiece.cc
    ${GP_SRC_DIR}/stubs/stringprintf.cc
    ${GP_SRC_DIR}/stubs/structurally_valid.cc
    ${GP_SRC_DIR}/stubs/strutil.cc
    ${GP_SRC_DIR}/stubs/time.cc
    ${GP_SRC_DIR}/wire_format_lite.cc)