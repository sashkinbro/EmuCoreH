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
project(testapp C CXX)
set(CMAKE_CXX_STANDARD 14)

set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++14 -Werror -Wthread-safety" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -D _LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS -Os -fPIC" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-rtti" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DGOOGLE_PROTOBUF_NO_RTTI -DHAVE_PTHREAD")

set( MEMORYADVICE_RESOURCES_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../memoryadvice/memoryadvice/src/main/resources")

file(GLOB TF_MODEL_FILES "${MEMORYADVICE_RESOURCES_DIR}/*.*")
file(COPY ${TF_MODEL_FILES} DESTINATION ${CMAKE_CURRENT_SOURCE_DIR}/../assets)

set (TEST_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../..")
add_subdirectory( "${TEST_DIR}" test_dir)

include_directories("${TEST_DIR}"
        "${TEST_DIR}/../../samples/common/include"
        )

add_library( ${PROJECT_NAME} SHARED testapp.cpp )

target_link_libraries( ${PROJECT_NAME}
        memory_advice_test_lib
        log )
