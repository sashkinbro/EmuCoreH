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

#include <string>

#include "session.h"
#include "tuningfork_internal.h"

namespace tuningfork {

class ActivityLifecycleState {
 public:
  ActivityLifecycleState();
  bool SetNewState(TuningFork_LifecycleState newState);
  TuningFork_LifecycleState GetCurrentState();
  bool IsAppOnForeground();
  virtual ~ActivityLifecycleState();
  CrashReason GetLatestCrashReason();

 private:
  bool app_on_foreground_ = false;
  std::string tf_lifecycle_path_str_;
  std::string tf_crash_info_file_;
  TuningFork_LifecycleState current_state_ = TUNINGFORK_STATE_UNINITIALIZED;

  const char* GetStateName(TuningFork_LifecycleState state);
  TuningFork_LifecycleState GetStateFromString(const std::string& name);
  TuningFork_LifecycleState GetStoredState();
  void StoreStateToDisk(TuningFork_LifecycleState state);

  static CrashReason GetReasonFromActivityManager();

  static CrashReason ConvertSignalToCrashReason(int signal);
};

}  // namespace tuningfork
