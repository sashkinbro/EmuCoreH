#
# Copyright (C) 2022 The Android Open Source Project
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
cmake_minimum_required(VERSION 3.10.0)
project(gameactivity C CXX)
set(CMAKE_CXX_STANDARD 17)

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/prefab-src/modules/game-activity/include/)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/../include/)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/../src/common/)

set(GAMEACTIVITY_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/prefab-src/modules/game-activity/src/")

set(GAMEACTIVITY_SRCS
    ${GAMEACTIVITY_SRC_DIR}/common/system_utils.cpp
    ${GAMEACTIVITY_SRC_DIR}/game-activity/GameActivity.cpp
    ${GAMEACTIVITY_SRC_DIR}/game-activity/GameActivityEvents.cpp
    ${GAMEACTIVITY_SRC_DIR}/game-activity/native_app_glue/android_native_app_glue.c
    ${GAMEACTIVITY_SRC_DIR}/game-text-input/gametextinput.cpp)
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Werror -Os")
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-exceptions -fno-rtti")
set( CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -g0")

# We use this empty library to distribute source files in our .aar
# See the build.gradle file
add_library(game-activity STATIC ${GAMEACTIVITY_SRCS})

add_library(game-activity_static STATIC ${GAMEACTIVITY_SRCS})

set_target_properties(game-activity PROPERTIES
     LIBRARY_OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/build )

set_target_properties(game-activity_static PROPERTIES
     LIBRARY_OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/build )
