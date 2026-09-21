/*
 * Copyright 2021 The Android Open Source Project
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

#include "common.h"

namespace tuningfork_test {

class TestMemInfoProvider : public tf::DefaultMemInfoProvider {
  uint64_t result_;

 public:
  TestMemInfoProvider(bool enabled) : result_(0), numNativeHeapRequests(0) {
    SetEnabled(enabled);
    SetDeviceMemoryBytes(8000000000);
  }
  uint64_t GetNativeHeapAllocatedSize() override {
    // Crank up the memory with each call
    result_ += 100000000L;
    ++numNativeHeapRequests;
    return result_;
  }
  uint64_t GetMemInfoOomScore() const override { return 42; }
  uint64_t GetAvailMem() override { return 234; }
  uint64_t GetPss() override { return 456; }
  void UpdateMemInfo() override {
    memInfo.active = std::make_pair(0, false);
    memInfo.activeAnon = std::make_pair(0, false);
    memInfo.activeFile = std::make_pair(0, false);
    memInfo.anonPages = std::make_pair(0, false);
    memInfo.commitLimit = std::make_pair(0, false);
    memInfo.highTotal = std::make_pair(0, false);
    memInfo.lowTotal = std::make_pair(0, false);
    memInfo.memAvailable = std::make_pair(0, false);

    memInfo.memFree = std::make_pair(200, true);

    memInfo.memTotal = std::make_pair(0, false);
    memInfo.swapTotal = std::make_pair(123, false);
    memInfo.vmData = std::make_pair(0, false);
    memInfo.vmRss = std::make_pair(0, false);
    memInfo.vmSize = std::make_pair(0, false);
  }
  void UpdateOomScore() override {}
  uint32_t numNativeHeapRequests;
};

}  // namespace tuningfork_test
