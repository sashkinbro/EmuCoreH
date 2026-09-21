
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

#include <cstdint>
#include <unordered_map>
#include <vector>

#include "common.h"
#include "metric.h"

namespace tuningfork {

// A histogram or time-series stored inside a Session.
struct MetricData {
  MetricData(Metric::Type t) : type(t) {}
  Metric::Type type;
  virtual ~MetricData() {}
  virtual void Clear() = 0;
  virtual size_t Count() const = 0;
  bool Empty() const { return Count() == 0; }
};

}  // namespace tuningfork
