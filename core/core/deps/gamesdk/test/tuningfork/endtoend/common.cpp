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

#include "common.h"

namespace tuningfork_test {

static const std::string kCacheDir = "/data/local/tmp/tuningfork_test";

const std::string session_context = R"TF(
{
  "device": {
    "brand": "",
    "build_version": "",
    "cpu_core_freqs_hz": [],
    "device": "",
    "fingerprint": "",
    "gles_version": {
      "major": 0,
      "minor": 0
    },
    "height_pixels": 0,
    "model": "",
    "product": "",
    "soc_manufacturer": "",
    "soc_model": "",
    "swap_total_bytes": 123,
    "total_memory_bytes": 0,
    "width_pixels": 0
  },
  "game_sdk_info": {
    "session_id": "",
    "version": "1.0.0"
  },
  "time_period": {
    "end_time": "1970-01-01T00:00:02.020000Z",
    "start_time": "1970-01-01T00:00:00.020000Z"
  }
}
)TF";

// This has extra time for the app and asset loading
const std::string session_context_loading = R"TF(
{
  "device": {
    "brand": "",
    "build_version": "",
    "cpu_core_freqs_hz": [],
    "device": "",
    "fingerprint": "",
    "gles_version": {
      "major": 0,
      "minor": 0
    },
    "height_pixels": 0,
    "model": "",
    "product": "",
    "soc_manufacturer": "",
    "soc_model": "",
    "swap_total_bytes": 123,
    "total_memory_bytes": 0,
    "width_pixels": 0
  },
  "game_sdk_info": {
    "session_id": "",
    "version": "1.0.0"
  },
  "time_period": {
    "end_time": "1970-01-01T00:00:02.220000Z",
    "start_time": "1970-01-01T00:00:00.220000Z"
  }
}
)TF";

const TuningFork_CProtobufSerialization* TrainingModeParams() {
    static tf::ProtobufSerialization pb = {1, 2, 3, 4, 5};
    static TuningFork_CProtobufSerialization cpb = {};
    cpb.bytes = pb.data();
    cpb.size = pb.size();
    return &cpb;
}

tf::Settings TestSettings(tf::Settings::AggregationStrategy::Submission method,
                          int n_ticks, int n_keys,
                          std::vector<uint32_t> annotation_size,
                          const std::vector<tf::Settings::Histogram>& hists,
                          int num_frame_time_histograms,
                          int num_loading_time_histograms) {
    tf::Settings s{};
    s.aggregation_strategy.method = method;
    s.aggregation_strategy.intervalms_or_count = n_ticks;
    s.aggregation_strategy.max_instrumentation_keys = n_keys;
    s.aggregation_strategy.annotation_enum_size = annotation_size;
    s.histograms = hists;
    s.c_settings.training_fidelity_params = TrainingModeParams();
    s.Check(kCacheDir);
    s.initial_request_timeout_ms = 5;
    s.ultimate_request_timeout_ms = 50;
    if (num_frame_time_histograms != 0)
        s.c_settings.max_num_metrics.frame_time = num_frame_time_histograms;
    s.c_settings.max_num_metrics.loading_time = num_loading_time_histograms;
    s.loading_annotation_index = -1;
    s.level_annotation_index = -1;
    return s;
}

std::string ReplaceReturns(std::string in) {
    std::replace(in.begin(), in.end(), '\n', ' ');
    return in;
}

}  // namespace tuningfork_test
