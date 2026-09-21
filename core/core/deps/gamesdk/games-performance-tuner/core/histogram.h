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

#include <inttypes.h>

#include <cmath>
#include <sstream>
#include <string>
#include <vector>

#include "tuningfork_internal.h"

#define LOG_TAG "TuningFork"
#include "Log.h"

namespace tuningfork {

struct HistogramBase {
  enum class Mode {
    HISTOGRAM = 0,   // Store in the buckets directly
    AUTO_RANGE = 1,  // Store a buffer of events until they fill samples_,
                     // then bucket
    EVENTS_ONLY = 2  // Store a circular buffer of events and never bucket them
  };
  static constexpr int kAutoSizeNumStdDev = 3;
  static constexpr double kAutoSizeMinBucketSizeMs = 0.1;
  static constexpr int kDefaultNumBuckets = 200;
};

template <typename Sample>
class Histogram : HistogramBase {
 public:
 private:
  Mode initial_mode_;
  Mode mode_;
  Sample start_, end_, bucket_size_;
  uint32_t num_buckets_;
  std::vector<uint32_t> buckets_;
  std::vector<Sample> samples_;
  size_t count_;
  size_t next_event_index_;

 public:
  explicit Histogram(Sample start = 0, Sample end = 0,
                     int num_buckets_between = kDefaultNumBuckets,
                     bool never_bucket = false);
  explicit Histogram(const Settings::Histogram&, bool never_bucket = false);

  // Add a sample delta time
  void Add(Sample sample);

  // Reset the histogram
  void Clear();

  // Get the total number of samples added so far
  size_t Count() const { return count_; }

  // Get the histogram as a JSON object, for testing
  std::string ToDebugJSON() const;

  // Use the data we have to construct the bucket ranges. This is called
  // automatically after the number of samples collected is the same as the
  // number of buckets, if we are auto-ranging.
  void CalcBucketsFromSamples();

  // Only to be used for testing
  void SetCounts(const std::vector<uint32_t>& counts) { buckets_ = counts; }

  TuningFork_ErrorCode AddCounts(const std::vector<uint32_t>& counts);

  bool operator==(const Histogram& h) const;

  const std::vector<uint32_t>& buckets() const { return buckets_; }

  const std::vector<Sample>& samples() const { return samples_; }

  Mode GetMode() const { return mode_; }
  Sample BucketStart() const { return start_; }
  Sample BucketEnd() const { return end_; }

  friend class ClearcutSerializer;
};

template <typename Sample>
Histogram<Sample>::Histogram(Sample start, Sample end, int num_buckets_between,
                             bool never_bucket)
    : initial_mode_(never_bucket
                        ? Mode::EVENTS_ONLY
                        : ((start == 0 && end == 0) ? Mode::AUTO_RANGE
                                                    : Mode::HISTOGRAM)),
      mode_(initial_mode_),
      start_(start),
      end_(end),
      bucket_size_((end_ - start_) /
                   (num_buckets_between <= 0 ? 1 : num_buckets_between)),
      num_buckets_(num_buckets_between <= 0 ? kDefaultNumBuckets
                                            : (num_buckets_between + 2)),
      buckets_(num_buckets_),
      count_(0),
      next_event_index_(0) {
  std::fill(buckets_.begin(), buckets_.end(), 0);
  switch (mode_) {
    case Mode::HISTOGRAM:
      if (bucket_size_ <= 0)
        ALOGE("Histogram end needs to be larger than histogram begin");
      break;
    case Mode::AUTO_RANGE:
      samples_.reserve(num_buckets_);
      break;
    case Mode::EVENTS_ONLY:
      samples_.resize(num_buckets_);
      break;
  }
}

template <typename Sample>
Histogram<Sample>::Histogram(const Settings::Histogram& hs, bool never_bucket)
    : Histogram(hs.bucket_min, hs.bucket_max, hs.n_buckets, never_bucket) {}

template <typename Sample>
void Histogram<Sample>::Add(Sample sample) {
  switch (mode_) {
    case Mode::HISTOGRAM: {
      int i = (sample - start_) / bucket_size_;
      if (i < 0)
        buckets_[0]++;
      else if (i + 1 >= num_buckets_)
        buckets_[num_buckets_ - 1]++;
      else
        buckets_[i + 1]++;
    } break;
    case Mode::AUTO_RANGE: {
      samples_.push_back(sample);
      if (samples_.size() >= num_buckets_) {
        CalcBucketsFromSamples();
      }
    } break;
    case Mode::EVENTS_ONLY: {
      samples_[next_event_index_++] = sample;
      if (next_event_index_ >= samples_.size()) next_event_index_ = 0;
    } break;
  }
  ++count_;
}

template <typename Sample>
void Histogram<Sample>::CalcBucketsFromSamples() {
  if (mode_ != Mode::AUTO_RANGE) return;
  Sample min_dt = std::numeric_limits<Sample>::max();
  Sample max_dt = std::numeric_limits<Sample>::min();
  // We use doubles to avoid overflow
  double sum = 0;
  double sum2 = 0;
  for (Sample d : samples_) {
    if (d < min_dt) min_dt = d;
    if (d > max_dt) max_dt = d;
    sum += d;
    sum2 += (double)d * (double)d;
  }
  size_t n = samples_.size();
  double mean = sum / n;
  double mean2 = mean * mean;
  sum2 /= n;
  double var = sum2 > mean2 ? sum2 - mean2 : 0;
  double stddev = sqrt(var);
  double stddev_scaled = kAutoSizeNumStdDev * stddev;
  start_ = mean > stddev_scaled ? mean - stddev_scaled : Sample();
  end_ = mean + stddev_scaled;
  bucket_size_ = (end_ - start_) / (num_buckets_ - 2);
  if (bucket_size_ < kAutoSizeMinBucketSizeMs) {
    bucket_size_ = kAutoSizeMinBucketSizeMs;
    Sample w = bucket_size_ * (num_buckets_ - 2);
    start_ = mean - w / 2;
    end_ = mean + w / 2;
  }
  mode_ = Mode::HISTOGRAM;
  count_ = 0;
  for (Sample d : samples_) {
    Add(d);
  }
}

template <typename Sample>
std::string Histogram<Sample>::ToDebugJSON() const {
  std::stringstream str;
  str.precision(2);
  str << std::fixed;
  if (mode_ != Mode::HISTOGRAM) {
    bool first = true;
    str << "{\"events\":[";
    for (int i = 0; i < samples_.size(); ++i) {
      if (!first)
        str << ',';
      else
        first = false;
      str << samples_[i];
    }
    str << "]}";
  } else {
    str << "{\"pmax\":[";
    Sample x = start_;
    for (int i = 0; i < num_buckets_ - 1; ++i) {
      str << x << ",";
      x += bucket_size_;
    }
    str << "99999],\"cnts\":[";
    for (int i = 0; i < num_buckets_ - 1; ++i) {
      str << buckets_[i] << ",";
    }
    if (num_buckets_ > 0) str << buckets_.back();
    str << "]}";
  }
  return str.str();
}

template <typename Sample>
void Histogram<Sample>::Clear() {
  std::fill(buckets_.begin(), buckets_.end(), 0);
  // Reset the mode so we switch back to auto-ranging if that was initially
  // specified.
  mode_ = initial_mode_;
  if (mode_ == Mode::EVENTS_ONLY) {
    std::fill(samples_.begin(), samples_.end(), 0.0);
    next_event_index_ = 0;
  } else {
    samples_.clear();
  }
  count_ = 0;
}

template <typename Sample>
bool Histogram<Sample>::operator==(const Histogram& h) const {
  return buckets_ == h.buckets_ && samples_ == h.samples_;
}

template <typename Sample>
TuningFork_ErrorCode Histogram<Sample>::AddCounts(
    const std::vector<uint32_t>& counts) {
  if (counts.size() != buckets_.size()) return TUNINGFORK_ERROR_BAD_PARAMETER;
  auto c = counts.begin();
  for (auto& c_orig : buckets_) {
    c_orig += *c++;
  }
  return TUNINGFORK_ERROR_OK;
}

}  // namespace tuningfork
