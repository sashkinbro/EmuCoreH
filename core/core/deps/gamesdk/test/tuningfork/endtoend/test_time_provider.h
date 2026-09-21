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

namespace tuningfork_test {

// Increment time with a known tick size
class TestTimeProvider : public tf::ITimeProvider {
 public:
  TestTimeProvider(tf::Duration tick_size_ = milliseconds(20),
                   tf::SystemDuration system_tick_size_ = milliseconds(20))
      : tick_size(tick_size_), system_tick_size(system_tick_size_) {
    Reset();
  }

  const tf::Duration kStartupTime = std::chrono::milliseconds(100);

  tf::TimePoint t;
  tf::SystemTimePoint st;
  tf::Duration tick_size;
  tf::SystemDuration system_tick_size;

  tf::TimePoint Now() override { return t; }

  tf::SystemTimePoint SystemNow() override { return st; }

  tf::Duration TimeSinceProcessStart() override {
    return Now() - tf::TimePoint{} + kStartupTime;
  }

  void Reset() {
    t = tf::TimePoint();
    st = tf::SystemTimePoint();
  }

  void Increment() {
    t += tick_size;
    st += system_tick_size;
  }
};

}  // namespace tuningfork_test
