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

namespace tuningfork {

struct BatteryMetric {
  int32_t percentage_, current_charge_;
  Duration time_since_process_start_;
  bool app_on_foreground_, is_charging_, power_save_mode_;

  BatteryMetric(int32_t percentage, int32_t currentCharge,
                const Duration& timeSinceProcessStart, bool appOnForeground,
                bool isCharging, bool powerSaveMode)
      : percentage_(percentage),
        current_charge_(currentCharge),
        time_since_process_start_(timeSinceProcessStart),
        app_on_foreground_(appOnForeground),
        is_charging_(isCharging),
        power_save_mode_(powerSaveMode) {}
};

struct BatteryMetricData : public MetricData {
  MetricId metric_id_;
  std::vector<BatteryMetric> data_;

  BatteryMetricData(MetricId metric_id)
      : MetricData(MetricType()), metric_id_(metric_id) {}

  void Record(bool app_on_foreground, Duration time_since_process_start,
              IBatteryProvider* battery_provider) {
    std::ostringstream out;
    out << time_since_process_start.count();
    BatteryMetric metric(battery_provider->GetBatteryPercentage(),
                         battery_provider->GetBatteryCharge(),
                         time_since_process_start, app_on_foreground,
                         battery_provider->IsBatteryCharging(),
                         battery_provider->IsPowerSaveModeEnabled());

    data_.push_back(metric);
  }

  virtual void Clear() override { data_.erase(data_.begin(), data_.end()); }
  virtual size_t Count() const override { return data_.size(); }
  static Metric::Type MetricType() { return Metric::Type::BATTERY; }
};

}  // namespace tuningfork
