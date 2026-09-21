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

#pragma once

#include "memory_advice/memory_advice.h"
#include "memory_advice/memory_advice_debug.h"

// Internal macros to generate a symbol to track Memory Advice version, do not
// use directly.
#define MEMORY_ADVICE_VERSION_CONCAT_NX(PREFIX, MAJOR, MINOR) \
  PREFIX##_##MAJOR##_##MINOR
#define MEMORY_ADVICE_VERSION_CONCAT(PREFIX, MAJOR, MINOR) \
  MEMORY_ADVICE_VERSION_CONCAT_NX(PREFIX, MAJOR, MINOR)
#define MEMORY_ADVICE_VERSION_SYMBOL                        \
  MEMORY_ADVICE_VERSION_CONCAT(MemoryAdvice_version,        \
                               MEMORY_ADVICE_MAJOR_VERSION, \
                               MEMORY_ADVICE_MINOR_VERSION)

// Internal function to track MemoryAdvice version bundled in a binary. Do not
// call directly. If you are getting linker errors related to
// MemoryAdvice_version_x_y, you probably have a mismatch between the header
// used at compilation and the actually library used by the linker.
extern "C" void MEMORY_ADVICE_VERSION_SYMBOL();

// These functions are implemented in memory_advice.cpp.
// They are mostly the same as the C interface, but take C++ types.

namespace memory_advice {

MemoryAdvice_ErrorCode Init();
MemoryAdvice_ErrorCode Init(const char* params);
MemoryAdvice_ErrorCode GetAdvice(MemoryAdvice_JsonSerialization* advice);
MemoryAdvice_MemoryState GetMemoryState();
int64_t GetAvailableMemory();
float GetPercentageAvailableMemory();
int64_t GetTotalMemory();
MemoryAdvice_ErrorCode RegisterWatcher(uint64_t intervalMillis,
                                       MemoryAdvice_WatcherCallback callback,
                                       void* user_data);
MemoryAdvice_ErrorCode UnregisterWatcher(MemoryAdvice_WatcherCallback callback);
int32_t BaseTests();

}  // namespace memory_advice
