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
project(swappy-test)

set (TEST_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../..")
add_subdirectory( "${TEST_DIR}" test_dir)

include_directories("${TEST_DIR}"
        "${TEST_DIR}/../../samples/common/include"
        )

add_library( ${PROJECT_NAME} SHARED
             swappy-test.cpp )


target_link_libraries( ${PROJECT_NAME}
                       swappy_test_lib
                       log )
