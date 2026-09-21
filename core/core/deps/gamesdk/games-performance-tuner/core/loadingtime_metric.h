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

#include "metricdata.h"
#include "process_time.h"
#include "settings.h"
#include "time_series.h"

namespace tuningfork {

struct LoadingTimeMetric {
  LoadingTimeMetric(SerializedAnnotation annotation = {})
      : annotation_(annotation) {}
  SerializedAnnotation annotation_;
};

struct LoadingTimeMetricData : public MetricData {
  static constexpr int kDefaultTimeSeriesCapacity = 200;
  LoadingTimeMetricData(MetricId metric_id)
      : MetricData(MetricType()),
        metric_id_(metric_id),
        data_(kDefaultTimeSeriesCapacity),
        duration_(Duration::zero()) {}
  MetricId metric_id_;
  TimeSeries<ProcessTimeInterval> data_;
  Duration duration_;
  void Record(Duration dt);
  void Record(ProcessTimeInterval interval);
  virtual void Clear() override {
    data_.Clear();
    duration_ = Duration::zero();
  }
  virtual size_t Count() const override { return data_.Count(); }
  static Metric::Type MetricType() { return Metric::Type::LOADING_TIME; }
};

struct LoadingTimeMetadataWithGroup {
  LoadingTimeMetadata metadata;
  std::string group_id;
  bool operator==(const tuningfork::LoadingTimeMetadataWithGroup& rhs) const {
    const tuningfork::LoadingTimeMetadata& x = metadata;
    const tuningfork::LoadingTimeMetadata& y = rhs.metadata;
    return x.state == y.state && x.source == y.source &&
           x.compression_level == y.compression_level &&
           x.network_connectivity == y.network_connectivity &&
           x.network_transfer_speed_bps == y.network_transfer_speed_bps &&
           x.network_latency_ns == y.network_latency_ns &&
           group_id == rhs.group_id;
  }
};

}  // namespace tuningfork

namespace std {

// Define hash function for LoadingTimeMetadata
template <class T>
inline void hash_combine(std::size_t& s, const T& v) {
  std::hash<T> h;
  s ^= h(v) + 0x9e3779b9 + (s << 6) + (s >> 2);
}
template <>
class hash<tuningfork::LoadingTimeMetadataWithGroup> {
 public:
  size_t operator()(const tuningfork::LoadingTimeMetadataWithGroup& md) const {
    const tuningfork::LoadingTimeMetadata& x = md.metadata;
    size_t result = 0;
    hash_combine(result, (uint64_t)x.state);
    hash_combine(result, (uint64_t)x.source);
    hash_combine(result, x.compression_level);
    hash_combine(result, (uint64_t)x.network_connectivity);
    hash_combine(result, x.network_transfer_speed_bps);
    hash_combine(result, x.network_latency_ns);
    hash_combine(result, md.group_id);
    return result;
  }
};
}  // namespace std
