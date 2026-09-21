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

#include "time_provider.h"
#include "tuningfork_utils.h"

namespace tuningfork {

TimePoint ChronoTimeProvider::Now() { return std::chrono::steady_clock::now(); }

SystemTimePoint ChronoTimeProvider::SystemNow() {
    return std::chrono::system_clock::now();
}

Duration ChronoTimeProvider::TimeSinceProcessStart() {
    return GetTimeSinceProcessStart();
}

}  // namespace tuningfork
