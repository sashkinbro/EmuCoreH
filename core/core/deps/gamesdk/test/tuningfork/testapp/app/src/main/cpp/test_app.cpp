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

#include <jni.h>

#include <sstream>
#include <string>

#include "tuningfork_test_c.h"
#define LOG_TAG "TestApp"
#include "Log.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_tuningfork_testapp_MainActivity_runTests(JNIEnv* env, jobject ctx) {
    int argc = 1;
    char appName[] = "testapp";
    char* argv[] = {appName};
    std::string full_record;
    int ret_code = shared_main(argc, argv, env, ctx, full_record);
    if (ret_code == 0) {
        ALOGV("%s", full_record.c_str());
    } else {
        std::stringstream ss(full_record);
        for (std::string record_line; std::getline(ss, record_line);) {
            ALOGE("%s", record_line.c_str());
        }
    }
    return env->NewStringUTF((full_record).c_str());
}
