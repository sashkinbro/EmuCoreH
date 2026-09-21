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

#include "histogram.h"
#include "memory_record_type.h"
#include "memory_telemetry.h"
#include "metricdata.h"
#include "settings.h"

namespace tuningfork {

// Set buffer to record up to 2 hours (one report per minute)
static const int32_t kBufferSize = 2 * 60;

static const Duration kMemoryMetricInterval = std::chrono::seconds(60);

struct MemoryMetric {
  int64_t avail_mem_, oom_score_, proportional_set_size_;
  Duration time_since_process_start_;

  MemoryMetric(int64_t avail_mem, int64_t oom_score,
               int64_t proportional_set_size, Duration time_since_process_start)
      : avail_mem_(avail_mem),
        oom_score_(oom_score),
        proportional_set_size_(proportional_set_size),
        time_since_process_start_(time_since_process_start) {}

  void UpdateValues(int64_t avail_mem, int64_t oom_score,
                    int64_t proportional_set_size,
                    Duration time_since_process_start) {
    avail_mem_ = avail_mem;
    oom_score_ = oom_score;
    proportional_set_size_ = proportional_set_size;
    time_since_process_start_ = time_since_process_start;
  }
};

struct MemoryMetricData : public MetricData {
  MemoryMetricData(MetricId metric_id)
      : MetricData(MetricType()), metric_id_(metric_id) {}
  MetricId metric_id_;
  std::vector<MemoryMetric> data_;
  int cyclical_buffer_location = 0;

  void Record(IMemInfoProvider *mem_info_provider,
              Duration time_since_process_start) {
    mem_info_provider->UpdateOomScore();
    if (data_.size() < kBufferSize) {
      MemoryMetric metric(mem_info_provider->GetAvailMem(),
                          mem_info_provider->GetMemInfoOomScore(),
                          mem_info_provider->GetPss(),
                          time_since_process_start);
      data_.push_back(metric);
    } else {
      data_[cyclical_buffer_location].UpdateValues(
          mem_info_provider->GetAvailMem(),
          mem_info_provider->GetMemInfoOomScore(), mem_info_provider->GetPss(),
          time_since_process_start);
      cyclical_buffer_location++;
      cyclical_buffer_location %= kBufferSize;
    }
  }
  virtual void Clear() override { data_.clear(); }
  virtual size_t Count() const override { return data_.size(); }
  static Metric::Type MetricType() { return Metric::Type::MEMORY; }
};

}  // namespace tuningfork
