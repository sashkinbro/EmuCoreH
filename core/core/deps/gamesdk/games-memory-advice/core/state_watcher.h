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

#include <atomic>
#include <memory>
#include <thread>

#include "memory_advice/memory_advice.h"

namespace memory_advice {

class MemoryAdviceImpl;

class StateWatcher {
 public:
  StateWatcher(MemoryAdviceImpl* impl, MemoryAdvice_WatcherCallback callback,
               void* user_data, uint64_t interval)
      : callback_(callback),
        user_data_(user_data),
        interval_(interval),
        do_cancel_(false),
        thread_running_(true),
        impl_(impl),
        thread_(std::make_unique<std::thread>(&StateWatcher::Looper, this)) {}
  virtual ~StateWatcher();
  void Cancel() { do_cancel_ = true; }
  bool ThreadRunning() const { return thread_running_; }
  const MemoryAdvice_WatcherCallback Callback() const { return callback_; }

 private:
  MemoryAdviceImpl* impl_;
  std::atomic<bool> do_cancel_;
  std::atomic<bool> thread_running_;
  std::unique_ptr<std::thread> thread_;
  MemoryAdvice_WatcherCallback callback_;
  void* user_data_;
  uint64_t interval_;
  void Looper();
};

}  // namespace memory_advice
