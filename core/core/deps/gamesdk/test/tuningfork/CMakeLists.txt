#
# Copyright 2018 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

cmake_minimum_required(VERSION 3.4.1)

find_package(games-performance-tuner REQUIRED CONFIG)

message( STATUS "A CMAKE_BUILD_TYPE = ${CMAKE_BUILD_TYPE}")

set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++14 -Werror" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -D _LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS -Os -fPIC" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-rtti" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DGOOGLE_PROTOBUF_NO_RTTI -DHAVE_PTHREAD")

set(ANDROID_GTEST_DIR "../../../external/googletest")
set(BUILD_GMOCK OFF)
set(INSTALL_GTEST OFF)
add_subdirectory("${ANDROID_GTEST_DIR}"
  googletest-build
)

include("../../games-performance-tuner/protobuf/protobuf.cmake")

# Save the generation dir as it gets overwritten when we add_subdirectory tuningfork
set(PGENS_DIR "${PROTO_GENS_DIR}")

protobuf_generate_lite_cpp( ${CMAKE_CURRENT_SOURCE_DIR}/proto
  proto/tuningfork.proto
  proto/dev_tuningfork.proto
)

option(TUNINGFORK_TEST_OPTION "" ON)

include_directories(
  "${ANDROID_GTEST_DIR}/googletest/include"
  ../../games-performance-tuner
  ../../src/common
  ../../include
  ../common
  ../../../external/nanopb-c
  ../../third_party
  ${PGENS_DIR}
  ${PROTOBUF_SRC_DIR}
  ${PROTOBUF_SRC_DIR}/..
  ${PROTOBUF_SRC_DIR}/../config/
)

set(TEST_SRCS
  annotation_test.cpp
  annotation_descriptor_test.cpp
  endtoend/abandoned_loading.cpp
  endtoend/annotation.cpp
  endtoend/battery.cpp
  endtoend/common.cpp
  endtoend/endtoend.cpp
  endtoend/fidelityparam_download.cpp
  endtoend/limits.cpp
  endtoend/loading.cpp
  endtoend/loading_groups.cpp
  endtoend/memory.cpp
  endtoend/trace.cpp
  endtoend/time_based.cpp
  file_cache_test.cpp
  histogram_test.cpp
  jni_test.cpp
  serialization_test.cpp
  settings_test.cpp
  ../common/test_utils.cpp
  ${PGENS_DIR}/lite/dev_tuningfork.pb.cc
  ${PGENS_DIR}/lite/tuningfork.pb.cc
)

add_executable(tuningfork_test
  main.cpp
  ${TEST_SRCS}
)

# This has to be a shared library because otherwise the linker doesn't see the tests.
add_library(tuningfork_test_lib
  SHARED
  shared_main.cpp
  ${TEST_SRCS}
)

add_library( protobuf-static
  STATIC ${PROTOBUF_LITE_SRCS}
)
target_compile_options(protobuf-static PUBLIC "-Wno-tautological-constant-compare"
                                              "-Wno-enum-compare-switch"
                                              "-Wno-invalid-noreturn")

target_link_libraries(tuningfork_test
  android
  gtest
  games-performance-tuner::tuningfork_static
  protobuf-static
  log
  GLESv2
  android
)
target_link_libraries(tuningfork_test_lib
  android
  gtest
  games-performance-tuner::tuningfork_static
  protobuf-static
  log
  GLESv2
)
