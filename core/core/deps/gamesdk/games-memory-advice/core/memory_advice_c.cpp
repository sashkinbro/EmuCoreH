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

#include "jni/jni_helper.h"
#include "memory_advice/memory_advice.h"
#include "memory_advice_internal.h"
#include "metrics_provider.h"

namespace jni = gamesdk::jni;

extern "C" {

MemoryAdvice_ErrorCode MemoryAdvice_init(JNIEnv *env, jobject context) {
    MEMORY_ADVICE_VERSION_SYMBOL();
    jni::Init(env, context);
    return memory_advice::Init();
}

MemoryAdvice_ErrorCode MemoryAdvice_initWithParams(JNIEnv *env, jobject context,
                                                   const char *params) {
    MEMORY_ADVICE_VERSION_SYMBOL();
    jni::Init(env, context);
    return memory_advice::Init(params);
}

MemoryAdvice_MemoryState MemoryAdvice_getMemoryState() {
    return memory_advice::GetMemoryState();
}

MemoryAdvice_ErrorCode MemoryAdvice_getAdvice(
    MemoryAdvice_JsonSerialization *advice) {
    return memory_advice::GetAdvice(advice);
}

MemoryAdvice_ErrorCode MemoryAdvice_registerWatcher(
    uint64_t intervalMillis, MemoryAdvice_WatcherCallback callback,
    void *user_data) {
    return memory_advice::RegisterWatcher(intervalMillis, callback, user_data);
}

MemoryAdvice_ErrorCode MemoryAdvice_unregisterWatcher(
    MemoryAdvice_WatcherCallback callback) {
    return memory_advice::UnregisterWatcher(callback);
}

int64_t MemoryAdvice_getAvailableMemory() {
    return memory_advice::GetAvailableMemory();
}

float MemoryAdvice_getPercentageAvailableMemory() {
    return memory_advice::GetPercentageAvailableMemory();
}

int64_t MemoryAdvice_getTotalMemory() {
    return memory_advice::GetTotalMemory();
}

void MemoryAdvice_JsonSerialization_free(MemoryAdvice_JsonSerialization *ser) {
    if (ser->dealloc) {
        ser->dealloc(ser);
        ser->dealloc = NULL;
    }
}

int32_t MemoryAdvice_test() { return memory_advice::BaseTests(); }

void MEMORY_ADVICE_VERSION_SYMBOL() {
    // Intentionally empty: this function is used to ensure that the proper
    // version of the library is linked against the proper headers.
    // In case of mismatch, a linker error will be triggered because of an
    // undefined symbol, as the name of the function depends on the version.
}

}  // extern "C"
