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
project(gametextinput C CXX)
set(CMAKE_CXX_STANDARD 17)

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/prefab-src/modules/game-text-input/include/)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/../include/)

set(GAMETEXTINPUT_SRCS
    ./prefab-src/modules/game-text-input/src/game-text-input/gametextinput.cpp)
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Werror -Os")
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-exceptions -fno-rtti")
set( CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -g0")

add_library(game-text-input STATIC ${GAMETEXTINPUT_SRCS})

add_library(game-text-input_static STATIC ${GAMETEXTINPUT_SRCS})

set_target_properties(game-text-input PROPERTIES
     LIBRARY_OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/build )

set_target_properties(game-text-input_static PROPERTIES
     LIBRARY_OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/build )
