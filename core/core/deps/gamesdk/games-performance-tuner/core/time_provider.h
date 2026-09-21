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

#include "common.h"

namespace tuningfork {

// You can provide your own time source rather than steady_clock by inheriting
// this and passing it to Init. Useful in tests.
class ITimeProvider {
 public:
  virtual ~ITimeProvider() {}
  virtual TimePoint Now() = 0;
  virtual SystemTimePoint SystemNow() = 0;
  virtual Duration TimeSinceProcessStart() = 0;
};

// Implementation that uses std::chrono.
class ChronoTimeProvider : public ITimeProvider {
 public:
  TimePoint Now() override;
  SystemTimePoint SystemNow() override;
  Duration TimeSinceProcessStart() override;
};

}  // namespace tuningfork
