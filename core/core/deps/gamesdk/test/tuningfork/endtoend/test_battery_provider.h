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
#include "core/battery_provider.h"

namespace tuningfork_test {

class TestBatteryProvider : public tf::IBatteryProvider {
  bool enabled_;

 public:
  TestBatteryProvider(bool enabled) : enabled_(enabled) {}

  int32_t GetBatteryPercentage() override { return 70; }

  int32_t GetBatteryCharge() override { return 1234; }

  tf::IBatteryProvider::ThermalState GetCurrentThermalStatus() override {
    return tf::IBatteryProvider::THERMAL_STATE_MODERATE;
  }

  bool IsBatteryCharging() override { return true; }

  bool IsPowerSaveModeEnabled() override { return true; }

  bool IsBatteryReportingEnabled() override { return enabled_; }
};

}  // namespace tuningfork_test