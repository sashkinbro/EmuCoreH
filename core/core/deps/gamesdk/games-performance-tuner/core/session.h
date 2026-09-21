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

#include <list>
#include <memory>
#include <mutex>
#include <unordered_map>

#include "battery_metric.h"
#include "frametime_metric.h"
#include "histogram.h"
#include "loadingtime_metric.h"
#include "memory_metric.h"
#include "proto/protobuf_util.h"
#include "thermal_metric.h"

namespace tuningfork {

struct TimeInterval {
  std::chrono::system_clock::time_point start, end;
};

typedef enum CrashReason {
  CRASH_REASON_UNSPECIFIED = 0,
  LOW_MEMORY = 1,
  SEGMENTATION_FAULT = 2,
  BUS_ERROR = 3,
  FLOATING_POINT_ERROR = 4,

} CrashReason;

// A recording session which stores histograms and time-series.
// These are double-buffered inside TuningForkImpl.
class Session {
 public:
  // Get functions return nullptr if there is no availability of this type
  // of metric left.
  template <typename T>
  T* GetData(MetricId id) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = metric_data_.find(id);
    if (it == metric_data_.end()) {
      MetricData* d;
      switch (T::MetricType()) {
        case Metric::Type::FRAME_TIME:
          d = TakeFrameTimeData(id);
          break;
        case Metric::Type::LOADING_TIME:
          d = TakeLoadingTimeData(id);
          break;
        case Metric::Type::MEMORY:
          d = TakeMemoryData(id);
          break;
        case Metric::Type::BATTERY:
          d = TakeBatteryData(id);
          break;
        case Metric::Type::THERMAL:
          d = TakeThermalData(id);
          break;
        case Metric::Type::ERROR:
          return nullptr;
      }
      if (d == nullptr) return nullptr;
      metric_data_.insert({id, d});
      return reinterpret_cast<T*>(d);
    }
    if (it->second->type == T::MetricType())
      return reinterpret_cast<T*>(it->second);
    else
      return nullptr;
  }

  // Create a FrameTimeHistogram and add it to the available histograms.
  FrameTimeMetricData* CreateFrameTimeHistogram(
      MetricId id, const Settings::Histogram& settings);

  // Create a LoadingTimeSeries and add it to the available loading time
  // series.
  LoadingTimeMetricData* CreateLoadingTimeSeries(MetricId id);

  // Create a memory histogram and add it to the available memory histograms.
  MemoryMetricData* CreateMemoryTimeSeries(MetricId id);

  // Create a BatteryTimeSeries and add it to the available battery data.
  BatteryMetricData* CreateBatteryTimeSeries(MetricId id);

  // Create a ThermalTimeSeries and add it to the available thermal data.
  ThermalMetricData* CreateThermalTimeSeries(MetricId id);

  // Clear the data in each created histogram or time series.
  void ClearData();

  template <typename T>
  std::vector<const T*> GetNonEmptyHistograms() const {
    // Note that this must only be called once the session has been frozen
    std::vector<const T*> ret;
    for (const auto& t : metric_data_) {
      if (!t.second->Empty()) {
        if (t.second->type == T::MetricType())
          ret.push_back(reinterpret_cast<T*>(t.second));
      }
    }
    return ret;
  }

  // Update times
  void Ping(SystemTimePoint t);

  TimeInterval time() const { return time_; }

  void SetInstrumentationKeys(const std::vector<InstrumentationKey>& ikeys) {
    instrumentation_keys_ = ikeys;
  }
  InstrumentationKey GetInstrumentationKey(uint32_t index) const {
    if (index < instrumentation_keys_.size())
      return instrumentation_keys_[index];
    else
      return 0;
  }

  void RecordCrash(CrashReason reason);
  std::vector<CrashReason> GetCrashReports() const;

  void SetFidelityParameters(ProtobufSerialization params) {
    current_fidelity_parameters = params;
  }
  ProtobufSerialization GetFidelityParameters() const {
    return current_fidelity_parameters;
  }

 private:
  // Get an available metric that has been set up to work with this id.
  FrameTimeMetricData* TakeFrameTimeData(MetricId id) {
    for (auto it = available_frame_time_data_.begin();
         it != available_frame_time_data_.end(); ++it) {
      auto p = *it;
      if (p->metric_id_.detail.frame_time.ikey == id.detail.frame_time.ikey) {
        available_frame_time_data_.erase(it);
        p->metric_id_ = id;
        return p;
      }
    }
    return nullptr;
  }

  // Get an available metric that has been set up to work with this id.
  LoadingTimeMetricData* TakeLoadingTimeData(MetricId id) {
    if (available_loading_time_data_.empty()) return nullptr;
    auto p = available_loading_time_data_.back();
    available_loading_time_data_.pop_back();
    p->metric_id_ = id;
    return p;
  }

  // Get an available metric that has been set up to work with this id.
  MemoryMetricData* TakeMemoryData(MetricId id) {
    if (available_memory_data_.empty()) return nullptr;
    auto p = available_memory_data_.back();
    available_memory_data_.pop_back();
    p->metric_id_ = id;
    return p;
  }

  // Get an available metric that has been set up to work with this id.
  BatteryMetricData* TakeBatteryData(MetricId id) {
    if (available_battery_data_.empty()) return nullptr;
    auto p = available_battery_data_.back();
    available_battery_data_.pop_back();
    p->metric_id_ = id;
    return p;
  }

  // Get an available metric that has been set up to work with this id.
  ThermalMetricData* TakeThermalData(MetricId id) {
    if (available_thermal_data_.empty()) return nullptr;
    auto p = available_thermal_data_.back();
    available_thermal_data_.pop_back();
    p->metric_id_ = id;
    return p;
  }

  TimeInterval time_ = {};
  std::vector<std::unique_ptr<FrameTimeMetricData>> frame_time_data_;
  std::vector<std::unique_ptr<LoadingTimeMetricData>> loading_time_data_;
  std::vector<std::unique_ptr<MemoryMetricData>> memory_data_;
  std::vector<std::unique_ptr<BatteryMetricData>> battery_data_;
  std::vector<std::unique_ptr<ThermalMetricData>> thermal_data_;
  std::list<FrameTimeMetricData*> available_frame_time_data_;
  std::vector<LoadingTimeMetricData*> available_loading_time_data_;
  std::vector<MemoryMetricData*> available_memory_data_;
  std::vector<BatteryMetricData*> available_battery_data_;
  std::vector<ThermalMetricData*> available_thermal_data_;
  std::unordered_map<MetricId, MetricData*> metric_data_;
  std::vector<CrashReason> crash_data_;
  std::vector<InstrumentationKey> instrumentation_keys_;
  std::mutex mutex_;
  mutable std::mutex crash_mutex_;
  ProtobufSerialization current_fidelity_parameters;
};

}  // namespace tuningfork
