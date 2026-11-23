# SPDX-License-Identifier: GPL-3.0-only
# Profile-guided optimisation for the C/C++ emulator cores.
#
#   EMUCORE_PGO_MODE=use (default)  apply <profile> when the file exists
#   EMUCORE_PGO_MODE=generate       instrument; the core calls emucore_pgo_flush()
#                                   on game unload (Android never runs DSO exit hooks)
#   EMUCORE_PGO_MODE=off            plain build
#
# Profiles are produced by the oracle workloads (tools/oracle) and merged with the
# NDK's llvm-profdata so the format always matches the NDK clang that consumes it.
set(EMUCORE_PGO_MODE "use" CACHE STRING "PGO mode for emulator cores: use, generate or off")
set(EMUCORE_PGO_DIR "${CMAKE_CURRENT_LIST_DIR}")

function(emucore_enable_pgo target name profile)
  if(EMUCORE_PGO_MODE STREQUAL "generate")
    target_compile_options(${target} PRIVATE -fprofile-generate)
    target_link_options(${target} PRIVATE -fprofile-generate)
    target_sources(${target} PRIVATE "${EMUCORE_PGO_DIR}/emucore_pgo.c")
    target_compile_definitions(${target} PRIVATE EMUCORE_PGO_GENERATE=1
      "EMUCORE_PGO_NAME=\"${name}\"")
    message(STATUS "${target}: PGO instrumentation enabled")
  elseif(EMUCORE_PGO_MODE STREQUAL "use" AND EXISTS "${profile}")
    target_compile_options(${target} PRIVATE "-fprofile-use=${profile}"
      -Wno-profile-instr-out-of-date -Wno-profile-instr-unprofiled -Wno-profile-instr-missing
      -Wno-backend-plugin)
    set_property(TARGET ${target} APPEND PROPERTY LINK_DEPENDS "${profile}")
    # Recompile every source when the profile changes.
    get_target_property(_pgo_sources ${target} SOURCES)
    set_property(SOURCE ${_pgo_sources} APPEND PROPERTY OBJECT_DEPENDS "${profile}")
    message(STATUS "${target}: using PGO profile ${profile}")
  endif()
endfunction()
