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

TuningForkLogEvent TestEndToEndTimeBased() {
    const int NTICKS =
        101;  // note the first tick doesn't add anything to the histogram
    auto settings =
        TestSettings(tf::Settings::AggregationStrategy::Submission::TIME_BASED,
                     10100, 1, {}, {{TFTICK_RAW_FRAME_TIME, 50, 150, 10}});
    TuningForkTest test(settings, milliseconds(100));
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

TEST(EndToEndTest, TimeBased) {
    auto result = TestEndToEndTimeBased();
    TuningForkLogEvent expected = R"TF(
{
  "name": "applications//apks/0",
  "session_context":)TF" + session_context +
                                  R"TF(,
  "telemetry": [{
    "context": {
      "annotations": "",
      "duration": "10s",
      "tuning_parameters": {
        "experiment_id": "",
        "serialized_fidelity_parameters": ""
      }
    },
    "report": {
      "rendering": {
        "render_time_histogram": [{
         "counts": [
           0, 0, 0, 0, 0, 0, 100, 0, 0, 0, 0, 0],
         "instrument_id": 64000
        }]
      }
    }
  }]
}
)TF";
    CheckStrings("TimeBased", result, expected);
}

TuningForkLogEvent TestEndToEndTimeBasedWithOnePause() {
    const int NTICKS =
        101;  // note the first tick doesn't add anything to the histogram
    auto settings =
        TestSettings(tf::Settings::AggregationStrategy::Submission::TIME_BASED,
                     10100, 1, {}, {{TFTICK_RAW_FRAME_TIME, 50, 150, 10}});
    TuningForkTest test(settings, milliseconds(100));
    std::unique_lock<std::mutex> lock(*test.rmutex_);
    for (int i = 0; i < NTICKS; ++i) {
        if (i > 75) {
            tf::ResumeFrameTimeLogging();
        } else if (i > 50) {
            tf::PauseFrameTimeLogging();
        }

        test.IncrementTime();
        tf::FrameTick(TFTICK_RAW_FRAME_TIME);
    }
    // Wait for the upload thread to complete writing the string
    EXPECT_TRUE(test.cv_->wait_for(lock, s_test_wait_time) ==
                std::cv_status::no_timeout)
        << "Timeout";

    return test.Result();
}

TEST(EndToEndTest, TimeBasedWithOnePause) {
    auto result = TestEndToEndTimeBasedWithOnePause();
    TuningForkLogEvent expected = R"TF(
{
  "name": "applications//apks/0",
  "session_context":)TF" + session_context +
                                  R"TF(,
  "telemetry": [{
    "context": {
      "annotations": "",
      "duration": "7.5s",
      "tuning_parameters": {
        "experiment_id": "",
        "serialized_fidelity_parameters": ""
      }
    },
    "report": {
      "rendering": {
        "render_time_histogram": [{
         "counts": [
           0, 0, 0, 0, 0, 0, 75, 0, 0, 0, 0, 0],
         "instrument_id": 64000
        }]
      }
    }
  }]
}
)TF";
    CheckStrings("TimeBased", result, expected);
}

TuningForkLogEvent TestEndToEndTimeBasedWithTwoPauses() {
    const int NTICKS =
        101;  // note the first tick doesn't add anything to the histogram
    auto settings =
        TestSettings(tf::Settings::AggregationStrategy::Submission::TIME_BASED,
                     10100, 1, {}, {{TFTICK_RAW_FRAME_TIME, 50, 150, 10}});
    TuningForkTest test(settings, milliseconds(90));
    std::unique_lock<std::mutex> lock(*test.rmutex_);
    EXPECT_FALSE(tf::IsFrameTimeLoggingPaused());

    for (int i = 0; i < NTICKS; ++i) {
        if (i > 40) {
            tf::ResumeFrameTimeLogging();
            EXPECT_FALSE(tf::IsFrameTimeLoggingPaused());
        } else if (i > 30) {
            tf::PauseFrameTimeLogging();
            EXPECT_TRUE(tf::IsFrameTimeLoggingPaused());
            test.time_provider_.tick_size = milliseconds(100);
        } else if (i > 20) {
            tf::ResumeFrameTimeLogging();
        } else if (i > 10) {
            tf::PauseFrameTimeLogging();
            test.time_provider_.tick_size = milliseconds(110);
        }

        test.IncrementTime();
        tf::FrameTick(TFTICK_RAW_FRAME_TIME);
    }
    // Wait for the upload thread to complete writing the string
    EXPECT_TRUE(test.cv_->wait_for(lock, s_test_wait_time) ==
                std::cv_status::no_timeout)
        << "Timeout";

    return test.Result();
}

TEST(EndToEndTest, TimeBasedWithTwoPauses) {
    auto result = TestEndToEndTimeBasedWithTwoPauses();
    TuningForkLogEvent expected = R"TF(
{
  "name": "applications//apks/0",
  "session_context":)TF" + session_context +
                                  R"TF(,
  "telemetry": [{
    "context": {
      "annotations": "",
      "duration": "8s",
      "tuning_parameters": {
        "experiment_id": "",
        "serialized_fidelity_parameters": ""
      }
    },
    "report": {
      "rendering": {
        "render_time_histogram": [{
         "counts": [
           0, 0, 0, 0, 0, 10, 60, 10, 0, 0, 0, 0],
         "instrument_id": 64000
        }]
      }
    }
  }]
}
)TF";
    CheckStrings("TimeBased", result, expected);
}

TuningForkLogEvent TimeBasedCheckUploadWhenPaused() {
    const int NTICKS =
        101;  // note the first tick doesn't add anything to the histogram
    auto settings =
        TestSettings(tf::Settings::AggregationStrategy::Submission::TIME_BASED,
                     10100, 1, {}, {{TFTICK_RAW_FRAME_TIME, 50, 150, 10}});
    TuningForkTest test(settings, milliseconds(100));
    std::unique_lock<std::mutex> lock(*test.rmutex_);
    for (int i = 0; i < NTICKS; ++i) {
        if (i > 40) {
            tf::PauseFrameTimeLogging();
        }
        test.IncrementTime();
        tf::FrameTick(TFTICK_RAW_FRAME_TIME);
    }
    // Wait for the upload thread to complete writing the string
    EXPECT_TRUE(test.cv_->wait_for(lock, s_test_wait_time) ==
                std::cv_status::no_timeout);
    return test.Result();
}

// Verify uploading still works when paused
TEST(EndToEndTest, TimeBasedCheckUploadWhenPaused) {
    auto result = TimeBasedCheckUploadWhenPaused();
    TuningForkLogEvent expected = R"TF(
{
  "name": "applications//apks/0",
  "session_context":
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
    "end_time": "!REGEX(.*?Z)",
    "start_time": "1970-01-01T00:00:00.020000Z"
  }
},
  "telemetry": [{
    "context": {
      "annotations": "",
      "duration": "!REGEX(.*?s)",
      "tuning_parameters": {
        "experiment_id": "",
        "serialized_fidelity_parameters": ""
      }
    },
    "report": {
      "rendering": {
        "render_time_histogram": [{
         "counts": [
           0, 0, 0, 0, 0, 0, 40, 0, 0, 0, 0, 0],
         "instrument_id": 64000
        }]
      }
    }
  }]
}
)TF";
    CheckStrings("TimeBased", result, expected);
}

TuningForkLogEvent TimeBasedCheckUploadWhenPausedAndFlushCalled() {
    auto settings =
        TestSettings(tf::Settings::AggregationStrategy::Submission::TIME_BASED,
                     10100, 1, {}, {{TFTICK_RAW_FRAME_TIME, 50, 150, 10}});
    TuningForkTest test(settings, milliseconds(100));
    std::unique_lock<std::mutex> lock(*test.rmutex_);
    for (int i = 0; i < 40; ++i) {
        test.IncrementTime();
        tf::FrameTick(TFTICK_RAW_FRAME_TIME);
    }
    // tf::PauseLogging(true);
    tf::Flush(true);

    // Wait for the upload thread to complete writing the string
    EXPECT_TRUE(test.cv_->wait_for(lock, s_test_wait_time) ==
                std::cv_status::no_timeout)
        << "Timeout";
    return test.Result();
}

// Verify flush still works when paused
TEST(EndToEndTest, TimeBasedCheckUploadWhenPausedAndFlushCalled) {
    auto result = TimeBasedCheckUploadWhenPausedAndFlushCalled();
    TuningForkLogEvent expected = R"TF(
{
  "name": "applications//apks/0",
  "session_context":
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
    "end_time": "!REGEX(.*?Z)",
    "start_time": "1970-01-01T00:00:00.020000Z"
  }
},
  "telemetry": [{
    "context": {
      "annotations": "",
      "duration": "!REGEX(.*?s)",
      "tuning_parameters": {
        "experiment_id": "",
        "serialized_fidelity_parameters": ""
      }
    },
    "report": {
      "rendering": {
        "render_time_histogram": [{
         "counts": [
           0, 0, 0, 0, 0, 0, 39, 0, 0, 0, 0, 0],
         "instrument_id": 64000
        }]
      }
    }
  }]
}
)TF";
    CheckStrings("TimeBased", result, expected);
}

}  // namespace tuningfork_test
