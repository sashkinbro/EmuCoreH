#
# Copyright 2020 The Android Open Source Project
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

set(CMAKE_CXX_STANDARD 17)

find_package(games-frame-pacing REQUIRED CONFIG)

message( STATUS "A CMAKE_BUILD_TYPE = ${CMAKE_BUILD_TYPE}")

set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++17 -Werror" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -D _LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS -Os -fPIC" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-rtti" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-exceptions" )

set(ANDROID_GTEST_DIR "../../../external/googletest")
set(BUILD_GMOCK OFF)
set(INSTALL_GTEST OFF)
add_subdirectory("${ANDROID_GTEST_DIR}"
   googletest-build
)

include_directories(
  "${ANDROID_GTEST_DIR}/googletest/include"
  ../../games-frame-pacing
  ../../src/common
  ../../include
)

set ( SOURCE_LOCATION_COMMON "../../games-frame-pacing/common" )

set(TEST_SRCS
  ${SOURCE_LOCATION_COMMON}/SwappyCommon.cpp
  ${SOURCE_LOCATION_COMMON}/CPUTracer.cpp
  ${SOURCE_LOCATION_COMMON}/CpuInfo.cpp
  ${SOURCE_LOCATION_COMMON}/Thread.cpp
  ${SOURCE_LOCATION_COMMON}/ChoreographerFilter.cpp
  ${SOURCE_LOCATION_COMMON}/ChoreographerThread.cpp
  ${SOURCE_LOCATION_COMMON}/SwappyDisplayManager.cpp
  ${SOURCE_LOCATION_COMMON}/Settings.cpp
  swappycommon_test.cpp
)

add_executable(swappy_test
  main.cpp
  ${TEST_SRCS}
)

add_library(swappy_test_lib
  SHARED
  shared_main.cpp
  ${TEST_SRCS}
)

target_link_libraries(swappy_test
  android
  gtest
  log
)

target_link_libraries(swappy_test_lib
  android
  gtest
  log
)
