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

#include <cstdint>

namespace tuningfork {

// Provider of system memory information.
class IMemInfoProvider {
 public:
  virtual ~IMemInfoProvider() {}
  virtual void UpdateMemInfo() = 0;
  virtual void UpdateOomScore() = 0;
  virtual uint64_t GetNativeHeapAllocatedSize() = 0;
  virtual uint64_t GetPss() = 0;
  virtual uint64_t GetAvailMem() = 0;
  virtual void SetEnabled(bool enable) = 0;
  virtual bool GetEnabled() const = 0;
  virtual void SetDeviceMemoryBytes(uint64_t bytesize) = 0;
  virtual uint64_t GetDeviceMemoryBytes() const = 0;
  virtual uint64_t GetMemInfoOomScore() const = 0;
  virtual bool IsMemInfoActiveAvailable() const = 0;
  virtual bool IsMemInfoActiveAnonAvailable() const = 0;
  virtual bool IsMemInfoActiveFileAvailable() const = 0;
  virtual bool IsMemInfoAnonPagesAvailable() const = 0;
  virtual bool IsMemInfoCommitLimitAvailable() const = 0;
  virtual bool IsMemInfoHighTotalAvailable() const = 0;
  virtual bool IsMemInfoLowTotalAvailable() const = 0;
  virtual bool IsMemInfoMemAvailableAvailable() const = 0;
  virtual bool IsMemInfoMemFreeAvailable() const = 0;
  virtual bool IsMemInfoMemTotalAvailable() const = 0;
  virtual bool IsMemInfoVmDataAvailable() const = 0;
  virtual bool IsMemInfoVmRssAvailable() const = 0;
  virtual bool IsMemInfoVmSizeAvailable() const = 0;
  virtual uint64_t GetMemInfoActiveBytes() const = 0;
  virtual uint64_t GetMemInfoActiveAnonBytes() const = 0;
  virtual uint64_t GetMemInfoActiveFileBytes() const = 0;
  virtual uint64_t GetMemInfoAnonPagesBytes() const = 0;
  virtual uint64_t GetMemInfoCommitLimitBytes() const = 0;
  virtual uint64_t GetMemInfoHighTotalBytes() const = 0;
  virtual uint64_t GetMemInfoLowTotalBytes() const = 0;
  virtual uint64_t GetMemInfoMemAvailableBytes() const = 0;
  virtual uint64_t GetMemInfoMemFreeBytes() const = 0;
  virtual uint64_t GetMemInfoMemTotalBytes() const = 0;
  virtual uint64_t GetMemInfoSwapTotalBytes() const = 0;
  virtual uint64_t GetMemInfoVmDataBytes() const = 0;
  virtual uint64_t GetMemInfoVmRssBytes() const = 0;
  virtual uint64_t GetMemInfoVmSizeBytes() const = 0;
};

}  // namespace tuningfork
