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
#include "test_utils.h"
#include "tuningfork_test.h"

using namespace gamesdk_test;

namespace tuningfork_test {

TuningForkLogEvent TestEndToEndWithMemory() {
    const int NTICKS =
        1001;  // note the first tick doesn't add anything to the histogram
    auto settings =
        TestSettings(tf::Settings::AggregationStrategy::Submission::TICK_BASED,
                     NTICKS - 1, 1, {});
    milliseconds tickDuration(20);
    TuningForkTest test(settings, tickDuration,
                        std::make_shared<TestDownloadBackend>(),
                        /*enable_meminfo*/ true);
    std::unique_lock<std::mutex> lock(*test.rmutex_);
    for (int i = 0; i < NTICKS; ++i) {
        test.IncrementTime();
        tuningfork::FrameTick(TFTICK_RAW_FRAME_TIME);
        // Put in a small sleep so we don't outpace the memory recording thread
        std::this_thread::sleep_for(milliseconds(1));
    }
    // Wait for the async metric thread to process 2s worth of memory requests
    test.WaitForMemoryUpdates((NTICKS * tickDuration) /
                              tf::kMemoryMetricInterval);
    // Wait for the upload thread to complete writing the string
    EXPECT_TRUE(test.cv_->wait_for(lock, s_test_wait_time) ==
                std::cv_status::no_timeout)
        << "Timeout";

    return test.Result();
}

TEST(EndToEndTest, WithMemory) {
    auto result = TestEndToEndWithMemory();
    TuningForkLogEvent expected = R"TF(
{
  "name": "applications//apks/0",
  "session_context":{
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
      "end_time": "1970-01-01T00:00:20.020000Z",
      "start_time": "1970-01-01T00:00:00.020000Z"
    }
  },
  "telemetry": [{
    "context": {
      "annotations": "",
      "duration": "20s",
      "tuning_parameters": {
        "experiment_id": "",
        "serialized_fidelity_parameters": ""
      }
    },
    "report": {
      "memory": {
        "memory_event":[
          {
            "avail_mem":234,
            "event_time":"!REGEX(.*?s)",
            "oom_score":42,
            "proportional_set_size":456
          }
        ]
      },
      "rendering": {
        "render_time_histogram": [{
         "counts": [**],
         "instrument_id": 64000
        }]
      }
    }
  }]
}
)TF";
    CheckStrings("WithMemory", result, expected);
}

}  // namespace tuningfork_test
