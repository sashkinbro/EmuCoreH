/*
 * Copyright 2022 The Android Open Source Project
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

#include "../core/memory_advice_impl.h"

namespace memory_advice {

constexpr int32_t OK_TEST = 0;
constexpr int32_t BAD_TEST = 1;
constexpr int64_t ONE_TERABYTE = 1LL << 40;

void watcher_callback(MemoryAdvice_MemoryState state, void* userData) {
    ALOGE("Callback should be cancelled before being called");
}

int32_t MemoryAdviceImpl::BaseTests() {
    MemoryAdvice_MemoryState state = GetMemoryState();
    if (state == MEMORYADVICE_STATE_UNKNOWN) {
        ALOGE("Expected state to not be UNKNOWN");
        return BAD_TEST;
    }
    int64_t memory_bytes = GetAvailableMemory();
    if (memory_bytes < 0) {
        ALOGE("Expected available memory to be positive");
        return BAD_TEST;
    }
    if (memory_bytes > ONE_TERABYTE) {
        ALOGE("Expected available memory to be less than 1TB");
        return BAD_TEST;
    }
    float percentage_avail = GetPercentageAvailableMemory();
    if (percentage_avail < 0 || percentage_avail > 100.0f) {
        ALOGE("Expected percentage available memory to be between 0 and 100");
        return BAD_TEST;
    }
    auto err = RegisterWatcher(1000, watcher_callback, nullptr);
    if (err != MEMORYADVICE_ERROR_OK) {
        ALOGE("Error registering watcher");
        return err;
    }
    err = UnregisterWatcher(watcher_callback);
    if (err != MEMORYADVICE_ERROR_OK) {
        ALOGE("Error unregistering watcher");
        return err;
    }
    return OK_TEST;
}

}  // namespace memory_advice
