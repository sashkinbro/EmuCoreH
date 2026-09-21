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

namespace tuningfork {

// Enum describing how the memory records were obtained.
enum MemoryRecordType {
  INVALID = 0,
  // From calls to android.os.Debug.getNativeHeapAllocatedSize
  ANDROID_DEBUG_NATIVE_HEAP,
  // From /proc/<PID>/oom_score file
  ANDROID_OOM_SCORE,
  // From /proc/meminfo and /proc/<PID>/status files
  ANDROID_MEMINFO_ACTIVE,
  ANDROID_MEMINFO_ACTIVEANON,
  ANDROID_MEMINFO_ACTIVEFILE,
  ANDROID_MEMINFO_ANONPAGES,
  ANDROID_MEMINFO_COMMITLIMIT,
  ANDROID_MEMINFO_HIGHTOTAL,
  ANDROID_MEMINFO_LOWTOTAL,
  ANDROID_MEMINFO_MEMAVAILABLE,
  ANDROID_MEMINFO_MEMFREE,
  ANDROID_MEMINFO_MEMTOTAL,
  ANDROID_MEMINFO_VMDATA,
  ANDROID_MEMINFO_VMRSS,
  ANDROID_MEMINFO_VMSIZE,
  COUNT  // Must be last entry
};
}  // namespace tuningfork
