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

#include "state_watcher.h"

#include "memory_advice_impl.h"

namespace memory_advice {

void StateWatcher::Looper() {
    thread_running_ = true;
    while (!do_cancel_) {
        std::this_thread::sleep_for(std::chrono::milliseconds(interval_));
        if (!do_cancel_) {
            MemoryAdvice_MemoryState state = impl_->GetMemoryState();
            if (state != MEMORYADVICE_STATE_OK && !do_cancel_) {
                callback_(state, user_data_);
            }
        }
    }
    thread_running_ = false;
}

StateWatcher::~StateWatcher() {
    if (!do_cancel_) {
        ALOGV(
            "memory_advice::StateWatcher::Cancel not called before delete? "
            "This can cause blocking on the main thread.");
        do_cancel_ = true;
    }
    thread_->join();
}

}  // namespace memory_advice
