#
# Copyright (C) 2021 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

cmake_minimum_required(VERSION 3.18.1)
project(paddleboat C CXX)
set(CMAKE_CXX_STANDARD 17)


set ( SOURCE_LOCATION .)
set ( SOURCE_LOCATION_COMMON "${SOURCE_LOCATION}/src/main/cpp" )
set ( SOURCE_LOCATION_TEST "${SOURCE_LOCATION}/src/androidTest/cpp" )

include_directories(${SOURCE_LOCATION_COMMON}/paddleboat/include)

set( PADDLEBOAT_SRCS
  ${SOURCE_LOCATION_COMMON}/InternalControllerTable.cpp
  ${SOURCE_LOCATION_COMMON}/GameController.cpp
  ${SOURCE_LOCATION_COMMON}/GameControllerDeviceInfo.cpp
  ${SOURCE_LOCATION_COMMON}/GameControllerLog.cpp
  ${SOURCE_LOCATION_COMMON}/GameControllerManager.cpp
  ${SOURCE_LOCATION_COMMON}/GameControllerMappingUtils.cpp
  ${SOURCE_LOCATION_COMMON}/paddleboat_c.cpp)

set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Werror -Os")
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-exceptions -fno-rtti")
set( CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -g0")

add_library(paddleboat_static STATIC ${PADDLEBOAT_SRCS})

set_target_properties( paddleboat_static PROPERTIES
     LIBRARY_OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/build )

add_library(paddleboat SHARED ${SOURCE_LOCATION_COMMON}/paddleboat_c.cpp)

target_link_libraries(paddleboat
  paddleboat_static
  android
  atomic
  log)

option(BUILD_TEST "Build native tests" OFF)
if(BUILD_TEST)

     find_package(googletest REQUIRED CONFIG)
     find_package(junit-gtest REQUIRED CONFIG)

     add_library(paddleboat_test SHARED ${SOURCE_LOCATION_TEST}/paddleboat_tests.cpp)
     target_link_libraries(paddleboat_test
       PRIVATE
         paddleboat_static
         android
         atomic
         log
         googletest::gtest
         googletest::gmock
         junit-gtest::junit-gtest
     )
endif()
unset(BUILD_TEST CACHE)
