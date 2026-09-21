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

namespace tuningfork {

// Provider for system battery and power information.
class IBatteryProvider {
 public:
  typedef enum ThermalState {
    // Device had no thermal state data, due to low api level.
    THERMAL_STATE_UNSPECIFIED = 0,
    // No thermal problems.
    THERMAL_STATE_NONE = 1,
    // Light throttling where UX is not impacted.
    THERMAL_STATE_LIGHT = 2,
    // Moderate throttling where UX is not largely impacted.
    THERMAL_STATE_MODERATE = 3,
    // Severe throttling where UX is largely impacted.
    THERMAL_STATE_SEVERE = 4,
    // Platform has done everything to reduce power.
    THERMAL_STATE_CRITICAL = 5,
    // Key components in platform are shutting down due to thermal
    // condition.
    // Device functionalities are limited.
    THERMAL_STATE_EMERGENCY = 6,
    // Indicates that the device needs shutdown immediately.
    THERMAL_STATE_SHUTDOWN = 7,
  } ThermalState;

  virtual ~IBatteryProvider() {}
  virtual int32_t GetBatteryPercentage() = 0;
  virtual int32_t GetBatteryCharge() = 0;
  virtual ThermalState GetCurrentThermalStatus() = 0;
  virtual bool IsBatteryCharging() = 0;
  virtual bool IsBatteryReportingEnabled() = 0;
  virtual bool IsPowerSaveModeEnabled() = 0;
};

// Implementation that uses JNI calls to BatteryManager and PowerManager.
class DefaultBatteryProvider : public IBatteryProvider {
 public:
  int32_t GetBatteryPercentage() override;
  int32_t GetBatteryCharge() override;
  ThermalState GetCurrentThermalStatus() override;
  bool IsBatteryCharging() override;
  bool IsBatteryReportingEnabled() override;
  bool IsPowerSaveModeEnabled() override;
};

}  // namespace tuningfork