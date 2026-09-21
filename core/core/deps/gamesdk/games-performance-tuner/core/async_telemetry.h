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

#include <deque>
#include <memory>

#include "core/runnable.h"
#include "core/time_provider.h"

namespace tuningfork {

class Session;

class RepeatingTask {
 public:
  RepeatingTask(Duration min_work_interval_in)
      : min_work_interval(min_work_interval_in) {}

  // Called by AsyncTelemetry to perform work.
  virtual void DoWork(Session* session) = 0;

 private:
  // When to wake to call this next.
  TimePoint next_time = TimePoint::min();
  // The minimum time between calling DoWork.
  Duration min_work_interval = Duration::zero();

  friend class AsyncTelemetry;
  friend class RepeatingTaskPtrComparator;
};

// Scheduler of metric recordings.
class AsyncTelemetry : public Runnable {
  // This is maintained as a heap, ordered by next_time.
  std::deque<std::shared_ptr<RepeatingTask>> metrics_;
  Session* session_ = 0;

 public:
  AsyncTelemetry(ITimeProvider* time_provider);
  void AddTask(const std::shared_ptr<RepeatingTask>& m);
  virtual Duration DoWork() override;
  void SetSession(Session* session) { session_ = session; }

  // How often to check if there has been any work added.
  static const Duration kNoWorkPollPeriod;
};

}  // namespace tuningfork
