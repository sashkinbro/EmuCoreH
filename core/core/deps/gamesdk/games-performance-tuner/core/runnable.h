/*
 * Copyright 2018 The Android Open Source Project
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

#include <condition_variable>
#include <mutex>
#include <thread>

#include "core/common.h"
#include "core/time_provider.h"

namespace tuningfork {

// Sub-class this class in order to create a separate thread that calls DoWork
// and then waits the time returned until calling it again.
class Runnable {
 protected:
  ITimeProvider* time_provider_;
  std::unique_ptr<std::thread> thread_;
  std::mutex mutex_;
  std::condition_variable cv_;
  bool do_quit_ = false;

 public:
  // If a time provider is supplied, waiting is done by polling the time
  // provider, which should be used only for tests.
  Runnable(ITimeProvider* time_provider = nullptr)
      : time_provider_(time_provider) {}
  virtual ~Runnable() {}
  virtual void Start();
  virtual void Run();
  virtual void Stop();
  // Return the time to wait before the next call
  virtual Duration DoWork() = 0;
};

}  // namespace tuningfork
