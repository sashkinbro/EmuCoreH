
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

#include "loadingtime_metric.h"

namespace tuningfork {

void LoadingTimeMetricData::Record(Duration dt) {
    if (dt.count() > 0) {
        data_.Add(dt);
        duration_ += dt;
    }
}

void LoadingTimeMetricData::Record(ProcessTimeInterval dt) {
    data_.Add(dt);
    duration_ += dt.Duration();
}

}  // namespace tuningfork
