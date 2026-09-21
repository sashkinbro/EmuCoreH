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

#include <android/api-level.h>

#include <chrono>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "battery_provider.h"
#include "histogram.h"
#include "jni/jni_wrap.h"
#include "metricdata.h"
#include "settings.h"
#include "system_utils.h"

namespace tuningfork {

struct ThermalMetric {
  IBatteryProvider::ThermalState thermal_state_;
  Duration time_since_process_start_;

  ThermalMetric(IBatteryProvider::ThermalState thermal_state,
                Duration time_since_process_start)
      : thermal_state_(thermal_state),
        time_since_process_start_(time_since_process_start) {}
};

struct ThermalMetricData : public MetricData {
  MetricId metric_id_;
  std::vector<ThermalMetric> data_;

  ThermalMetricData(MetricId metric_id)
      : MetricData(MetricType()), metric_id_(metric_id) {}

  void Record(Duration time_since_process_start,
              IBatteryProvider* battery_provider) {
    ThermalMetric metric(battery_provider->GetCurrentThermalStatus(),
                         time_since_process_start);
    data_.push_back(metric);
  }

  virtual void Clear() override { data_.erase(data_.begin(), data_.end()); }
  virtual size_t Count() const override { return data_.size(); }
  static Metric::Type MetricType() { return Metric::Type::THERMAL; }
};

}  // namespace tuningfork
