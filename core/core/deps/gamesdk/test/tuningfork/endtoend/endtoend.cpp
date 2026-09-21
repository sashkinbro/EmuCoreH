/*
 * Copyright 2019 The Android Open Source Project
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

TuningForkLogEvent TestEndToEnd() {
    const int NTICKS =
        101;  // note the first tick doesn't add anything to the histogram
    auto settings =
        TestSettings(tf::Settings::AggregationStrategy::Submission::TICK_BASED,
                     NTICKS - 1, 1, {});
    TuningForkTest test(settings);
    std::unique_lock<std::mutex> lock(*test.rmutex_);
    for (int i = 0; i < NTICKS; ++i) {
        test.IncrementTime();
        tf::FrameTick(TFTICK_RAW_FRAME_TIME);
    }
    // Wait for the upload thread to complete writing the string
    EXPECT_TRUE(test.cv_->wait_for(lock, s_test_wait_time) ==
                std::cv_status::no_timeout)
        << "Timeout";

    return test.Result();
}

TEST(EndToEndTest, Base) {
    auto result = TestEndToEnd();
    TuningForkLogEvent expected = R"TF(
{
  "name": "applications//apks/0",
  "session_context":)TF" + session_context +
                                  R"TF(,
  "telemetry": [{
    "context": {
      "annotations": "",
      "duration": "2s",
      "tuning_parameters": {
        "experiment_id": "",
        "serialized_fidelity_parameters": ""
      }
    },
    "report": {
      "rendering": {
        "render_time_histogram": [{
         "counts": [
           0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
           0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0,
           0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
           0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
           0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
           0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
           0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
         "instrument_id": 64000
        }]
      }
    }
  }]
}
)TF";
    CheckStrings("Base", result, expected);
}

}  // namespace tuningfork_test
