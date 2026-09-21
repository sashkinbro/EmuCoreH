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

#pragma once

#include <core/metrics_provider.h>

using namespace json11;

namespace memory_advice_test {

class TestMetricsProvider : public memory_advice::IMetricsProvider {
  double oom_score_ = 0;
  double avail_mem_ = 0;
  double swap_total_ = 0;
  double total_mem_ = 0;

 public:
  void setOomScore(double oom_score) { oom_score_ = oom_score; }
  void setSwapTotal(double swap_total) { swap_total_ = swap_total; }
  void setAvailMem(double avail_mem) { avail_mem_ = avail_mem; }
  void setTotalMem(double total_mem) { total_mem_ = total_mem; }

  Json::object GetMeminfoValues() override {
    Json::object metrics_map;
    metrics_map["SwapTotal"] = swap_total_;
    return metrics_map;
  }

  Json::object GetStatusValues() override {
    Json::object metrics_map;
    return metrics_map;
  }

  Json::object GetProcValues() override {
    Json::object metrics_map;
    metrics_map["oom_score"] = oom_score_;
    return metrics_map;
  }

  Json::object GetActivityManagerValues() override {
    Json::object metrics_map;
    return metrics_map;
  }

  Json::object GetActivityManagerMemoryInfo() override {
    Json::object metrics_map;
    metrics_map["availMem"] = avail_mem_;
    metrics_map["totalMem"] = total_mem_;
    return metrics_map;
  }

  Json::object GetDebugValues() override {
    Json::object metrics_map;
    return metrics_map;
  }
};

}  // namespace memory_advice_test
