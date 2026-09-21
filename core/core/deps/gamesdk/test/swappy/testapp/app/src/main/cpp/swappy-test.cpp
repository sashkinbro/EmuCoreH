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

#include <string>

#include "swappy_test.h"
#define LOG_TAG "TestApp"
#include "Log.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_swappy_testapp_MainActivity_runTests(JNIEnv* env, jobject ctx) {
    int argc = 1;
    char appName[] = "testapp";
    char* argv[] = {appName};
    std::string full_record;
    int ret_code = shared_main(argc, argv, env, ctx, full_record);
    if (ret_code == 0) {
        ALOGV("%s", full_record.c_str());
    } else {
        ALOGE("%s", full_record.c_str());
    }
    return env->NewStringUTF(full_record.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_swappy_testapp_MainActivity_testSummarySoFar(JNIEnv* env,
                                                      jobject ctx) {
    constexpr int BUF_LEN = 2048;
    static char buf[BUF_LEN] = "";
    test_summary(buf, BUF_LEN);
    return env->NewStringUTF(buf);
}
