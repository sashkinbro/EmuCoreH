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

typedef std::chrono::nanoseconds ProcessTime;  // Duration since process start.

// This can represent an interval with start and end times or a pure duration
// if end.count()==0.
class ProcessTimeInterval {
  ProcessTime start_;
  ProcessTime end_;

 public:
  ProcessTimeInterval() : start_(0), end_(0) {}
  // Initialize as a duration.
  ProcessTimeInterval(Duration duration) : start_(duration), end_(0) {}
  // Initialize as an interval.
  ProcessTimeInterval(ProcessTime start, ProcessTime end)
      : start_(start), end_(end) {
    // Disallow travelling backwards in time.
    if (start_ > end_) {
      end_ = start_;
    }
  }
  bool IsDuration() const { return end_.count() == 0; }
  Duration Duration() const {
    if (IsDuration())
      return start_;
    else
      return end_ - start_;
  }
  ProcessTime Start() const { return start_; }
  ProcessTime End() const { return end_; }
};

}  // namespace tuningfork
