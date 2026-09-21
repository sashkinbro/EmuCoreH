
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

#include "frametime_metric.h"

namespace tuningfork {

void FrameTimeMetricData::Tick(TimePoint t, bool record) {
    if (last_time_ != TimePoint::min() && t > last_time_ && record)
        Record(t - last_time_);
    last_time_ = t;
}

void FrameTimeMetricData::Record(Duration dt) {
    if (dt.count() > 0) {
        // The histogram stores millisecond values as doubles
        histogram_.Add(
            double(std::chrono::duration_cast<std::chrono::nanoseconds>(dt)
                       .count()) /
            1000000);
        // The values are stored in the kll aggregator as microseconds
        aggregator_->Add(int64_t(
            std::chrono::duration_cast<std::chrono::microseconds>(dt).count()));
        duration_ += dt;
    }
}

void FrameTimeMetricData::Clear() {
    last_time_ = TimePoint::min();
    histogram_.Clear();
    duration_ = Duration::zero();
    aggregator_->Reset();
}

}  // namespace tuningfork
