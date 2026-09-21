#
# Copyright 2022 The Android Open Source Project
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

message( STATUS "A CMAKE_BUILD_TYPE = ${CMAKE_BUILD_TYPE}")

set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++14 -Werror" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -D _LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS -Os -fPIC" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-rtti" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DGOOGLE_PROTOBUF_NO_RTTI -DHAVE_PTHREAD")

set( TENSORFLOW_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../external/tensorflow")
set( TFLITE_FLATBUFFERS_SCHEMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../external/flatbuffers/include")

set( MEMORYADVICE_ASSETS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../memoryadvice/memoryadvice/src/main/assets/memoryadvice")
set( MEMORYADVICE_RESOURCES_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../memoryadvice/memoryadvice/src/main/resources")

file(READ "${MEMORYADVICE_ASSETS_DIR}/default.json" PARAMS_FILE)
set(PARAMS_STRING "namespace memory_advice_test {\nconst char* parameters_string = R\"PARAMS(\n")
string(APPEND PARAMS_STRING "${PARAMS_FILE}")
string(APPEND PARAMS_STRING "\n )PARAMS\";\n}\n")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/advisor_parameters.cpp" "${PARAMS_STRING}")

set(ANDROID_GTEST_DIR "../../../external/googletest")
set(BUILD_GMOCK OFF)
set(INSTALL_GTEST OFF)
add_subdirectory("${ANDROID_GTEST_DIR}"
  googletest-build
)


option(TUNINGFORK_TEST_OPTION "" ON)

include("../../samples/gamesdk.cmake")

#This builds the gamesdk in package/localtf and adds a gamesdk static library target
add_gamesdk_target(
        DO_LOCAL_BUILD
        ROOT_DIR "../.."
        PACKAGE_DIR "../../../package/local"
        LIBRARIES "memory_advice"
)

include_directories(
  "${ANDROID_GTEST_DIR}/googletest/include"
  ../../src/memory_advice
  ../../src/common
  ../common
  ../../include
  ../../../external/nanopb-c
  ../../third_party
  .
)

set(TFLITE_INCLUDE_DIRS
  "${TENSORFLOW_SOURCE_DIR}"
  "${TFLITE_FLATBUFFERS_SCHEMA_DIR}"
)
include_directories(
  BEFORE
  ${TFLITE_INCLUDE_DIRS}
)

set(TEST_SRCS
        endtoend/endtoend.cpp
        endtoend/withallocation.cpp
        endtoend/withmockmetrics.cpp
        memory_utils.cpp
        ../common/test_utils.cpp
        ${CMAKE_CURRENT_BINARY_DIR}/advisor_parameters.cpp
)

add_library(memory_advice_test_lib
  STATIC
  shared_main.cpp
  ${TEST_SRCS}
)

target_link_libraries(memory_advice_test_lib
  android
  gtest
  memory_advice
  log
  GLESv2
)

