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

#include <utility>

#include "core/async_telemetry.h"
#include "core/common.h"
#include "core/histogram.h"
#include "core/memory_metric.h"
#include "jni/jni_wrap.h"
#include "session.h"

namespace tuningfork {

class Session;

class MemoryTelemetry {
 public:
  static Duration UploadPeriod();
};

struct MemInfo {
  bool initialized = false;
  uint32_t pid;
  int oom_score;
  std::pair<uint64_t, bool> active;
  std::pair<uint64_t, bool> activeAnon;
  std::pair<uint64_t, bool> activeFile;
  std::pair<uint64_t, bool> anonPages;
  std::pair<uint64_t, bool> commitLimit;
  std::pair<uint64_t, bool> highTotal;
  std::pair<uint64_t, bool> lowTotal;
  std::pair<uint64_t, bool> memAvailable;
  std::pair<uint64_t, bool> memFree;
  std::pair<uint64_t, bool> memTotal;
  std::pair<uint64_t, bool> swapTotal;
  std::pair<uint64_t, bool> vmData;
  std::pair<uint64_t, bool> vmRss;
  std::pair<uint64_t, bool> vmSize;
};

class DefaultMemInfoProvider : public IMemInfoProvider {
  bool enabled_ = true;
  uint64_t device_memory_bytes = 0;
  gamesdk::jni::android::os::DebugClass android_debug_;
  gamesdk::jni::android::os::Process android_process_;

 protected:
  MemInfo memInfo;

 public:
  DefaultMemInfoProvider();
  void UpdateMemInfo() override;
  void UpdateOomScore() override;
  uint64_t GetNativeHeapAllocatedSize() override;
  uint64_t GetPss() override;
  uint64_t GetAvailMem() override;
  void SetEnabled(bool enable) override;
  bool GetEnabled() const override;
  void SetDeviceMemoryBytes(uint64_t bytesize) override;
  uint64_t GetDeviceMemoryBytes() const override;
  uint64_t GetMemInfoOomScore() const override;
  bool IsMemInfoActiveAvailable() const override;
  bool IsMemInfoActiveAnonAvailable() const override;
  bool IsMemInfoActiveFileAvailable() const override;
  bool IsMemInfoAnonPagesAvailable() const override;
  bool IsMemInfoCommitLimitAvailable() const override;
  bool IsMemInfoHighTotalAvailable() const override;
  bool IsMemInfoLowTotalAvailable() const override;
  bool IsMemInfoMemAvailableAvailable() const override;
  bool IsMemInfoMemFreeAvailable() const override;
  bool IsMemInfoMemTotalAvailable() const override;
  bool IsMemInfoVmDataAvailable() const override;
  bool IsMemInfoVmRssAvailable() const override;
  bool IsMemInfoVmSizeAvailable() const override;
  uint64_t GetMemInfoActiveBytes() const override;
  uint64_t GetMemInfoActiveAnonBytes() const override;
  uint64_t GetMemInfoActiveFileBytes() const override;
  uint64_t GetMemInfoAnonPagesBytes() const override;
  uint64_t GetMemInfoCommitLimitBytes() const override;
  uint64_t GetMemInfoHighTotalBytes() const override;
  uint64_t GetMemInfoLowTotalBytes() const override;
  uint64_t GetMemInfoMemAvailableBytes() const override;
  uint64_t GetMemInfoMemFreeBytes() const override;
  uint64_t GetMemInfoMemTotalBytes() const override;
  uint64_t GetMemInfoSwapTotalBytes() const override;
  uint64_t GetMemInfoVmDataBytes() const override;
  uint64_t GetMemInfoVmRssBytes() const override;
  uint64_t GetMemInfoVmSizeBytes() const override;
};

class MemoryReportingTask : public RepeatingTask {
 protected:
  IMemInfoProvider* mem_info_provider_;
  MetricId metric_id_;
  ITimeProvider* time_provider_;

 public:
  MemoryReportingTask(ITimeProvider* time_provider, IMemInfoProvider* m,
                      MetricId metric_id)
      : RepeatingTask(MemoryTelemetry::UploadPeriod()),
        mem_info_provider_(m),
        metric_id_(metric_id),
        time_provider_(time_provider) {}
  virtual void DoWork(Session* session) override;
  void UpdateMetricId(MetricId id);
};

}  // namespace tuningfork
