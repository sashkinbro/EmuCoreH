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

#include "memory_advice/memory_advice.h"

#include <string>

#include "memory_advice_impl.h"
#include "memory_advice_internal.h"
#include "state_watcher.h"

#define LOG_TAG "MemoryAdvice"
#include "Log.h"
#include "json11/json11.hpp"

namespace memory_advice {

using namespace json11;

extern const char* parameters_string;

static MemoryAdviceImpl* s_impl;

MemoryAdvice_ErrorCode Init(const char* params) {
    if (s_impl != nullptr) return MEMORYADVICE_ERROR_ALREADY_INITIALIZED;
    s_impl = new MemoryAdviceImpl(params, nullptr, nullptr, nullptr);
    return s_impl->InitializationErrorCode();
}

MemoryAdvice_ErrorCode Init() { return Init(parameters_string); }

extern "C" void MemoryAdvice_JsonSerialization_Dealloc(
    MemoryAdvice_JsonSerialization* c) {
    if (c->json) {
        free(c->json);
        c->json = nullptr;
        c->size = 0;
    }
}

MemoryAdvice_ErrorCode GetAdvice(MemoryAdvice_JsonSerialization* advice) {
    if (s_impl == nullptr) return MEMORYADVICE_ERROR_NOT_INITIALIZED;
    std::string dump = Json(s_impl->GetAdvice()).dump();

    advice->json = (char*)malloc(dump.length() + 1);
    strcpy(advice->json, dump.c_str());
    advice->size = dump.length();
    advice->dealloc = MemoryAdvice_JsonSerialization_Dealloc;

    return MEMORYADVICE_ERROR_OK;
}

MemoryAdvice_MemoryState GetMemoryState() {
    if (s_impl == nullptr)
        return static_cast<MemoryAdvice_MemoryState>(
            MEMORYADVICE_ERROR_NOT_INITIALIZED);
    return s_impl->GetMemoryState();
}

int64_t GetAvailableMemory() {
    if (s_impl == nullptr)
        return static_cast<int64_t>(MEMORYADVICE_ERROR_NOT_INITIALIZED);
    return s_impl->GetAvailableMemory();
}

float GetPercentageAvailableMemory() {
    if (s_impl == nullptr)
        return static_cast<float>(MEMORYADVICE_ERROR_NOT_INITIALIZED);
    return s_impl->GetPercentageAvailableMemory();
}

int64_t GetTotalMemory() {
    if (s_impl == nullptr)
        return static_cast<int64_t>(MEMORYADVICE_ERROR_NOT_INITIALIZED);
    return s_impl->GetTotalMemory();
}

MemoryAdvice_ErrorCode RegisterWatcher(uint64_t intervalMillis,
                                       MemoryAdvice_WatcherCallback callback,
                                       void* user_data) {
    if (s_impl == nullptr) return MEMORYADVICE_ERROR_NOT_INITIALIZED;
    return s_impl->RegisterWatcher(intervalMillis, callback, user_data);
}

MemoryAdvice_ErrorCode UnregisterWatcher(
    MemoryAdvice_WatcherCallback callback) {
    if (s_impl == nullptr) return MEMORYADVICE_ERROR_NOT_INITIALIZED;
    return s_impl->UnregisterWatcher(callback);
}

int32_t BaseTests() {
    if (s_impl == nullptr) return MEMORYADVICE_ERROR_NOT_INITIALIZED;
    return s_impl->BaseTests();
}

}  // namespace memory_advice
