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

#include "async_telemetry.h"

#include <algorithm>
#include <memory>

namespace tuningfork {

using namespace std::chrono;

const Duration AsyncTelemetry::kNoWorkPollPeriod = milliseconds(100);

// Compare by the task's next_time.
// We want the lowest time at the top of the heap.
struct RepeatingTaskPtrComparator {
    bool operator()(const std::shared_ptr<RepeatingTask>& a,
                    const std::shared_ptr<RepeatingTask>& b) const {
        return a->next_time > b->next_time;
    }
};

AsyncTelemetry::AsyncTelemetry(ITimeProvider* time_provider)
    : Runnable(time_provider) {}

void AsyncTelemetry::AddTask(const std::shared_ptr<RepeatingTask>& m) {
    metrics_.push_back(m);
    std::push_heap(metrics_.begin(), metrics_.end(),
                   RepeatingTaskPtrComparator());
}

Duration AsyncTelemetry::DoWork() {
    if (metrics_.empty()) return kNoWorkPollPeriod;
    // Look at the queue of metrics and see if we need to execute them.
    while (true) {
        auto m = metrics_.front();
        auto now = time_provider_->Now();
        if (m->next_time > now) return m->next_time - now;
        m->DoWork(session_);
        auto elapsed = time_provider_->Now() - now;
        m->next_time = now + elapsed + m->min_work_interval;
        std::make_heap(metrics_.begin(), metrics_.end(),
                       RepeatingTaskPtrComparator());
    }
}

}  // namespace tuningfork
