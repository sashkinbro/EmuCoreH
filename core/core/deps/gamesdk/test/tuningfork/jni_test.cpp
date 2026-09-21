/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "gtest/gtest.h"
#include "jni/jni_helper.h"

// This function must be declared externally and should call jni::Init and
// return true if a java environment is available. If there is no Java env
// available, return false.
extern "C" bool init_jni_for_tests();

using namespace gamesdk;

TEST(JNI, Init) {
    EXPECT_EQ(jni::IsValid(), false) << ": should not be valid before init";
    if (init_jni_for_tests()) {
        EXPECT_EQ(jni::IsValid(), true) << ": should be valid after init";
    }
}
