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

static std::string AbandonedLoadingEvent(int type, const std::string& duration,
                                         const std::string& interval,
                                         bool add_group = false) {
    std::string any_string = "\"!REGEX([^\"]*)\"";
    std::stringstream str;
    str << R"TF(
{ "name": "applications//apks/0",
  "session_context": {
    "device": {
      "brand": "",
      "build_version": "",
      "cpu_core_freqs_hz": [],
      "device": "",
      "fingerprint": "",
      "gles_version": {"major": 0, "minor": 0},
      "height_pixels": 0,
      "model": "",
      "product": "",
      "soc_manufacturer": "",
      "soc_model": "",
      "swap_total_bytes": 123,
      "total_memory_bytes": 0,
      "width_pixels": 0
    },
    "game_sdk_info": {"session_id": "", "version": "1.0.0"},
    "time_period": {"end_time": "1970-01-01T00:00:00.000000Z",
                    "start_time": "1970-01-01T00:00:00.000000Z"}
  },
  "telemetry": [
)TF";
    if (add_group) {
        str << R"TF({
    "context": {
      "annotations": "",
      "duration": ")TF";
        str << duration;
        str << R"TF(",
      "tuning_parameters": {
        "experiment_id": "",
        "serialized_fidelity_parameters": ""
      }
    },
    "report": {
      "partial_loading": {
        "event_type":
)TF";
        str << type;
        str << R"TF(,
        "report": {
          "loading_events": [{
            "intervals": [
)TF";
        str << interval;
        str << R"TF(],
            "loading_metadata": {
              "group_id": )TF"
            << any_string << R"TF(,
              "source": 9,
              "state": 1
            }
          }]
        }
      }
    }
  },)TF";
    }
    str << R"TF({
    "context": {
      "annotations": "AQID",
      "duration": ")TF";
    str << duration;
    str << R"TF(",
      "tuning_parameters": {
        "experiment_id": "",
        "serialized_fidelity_parameters": ""
      }
    },
    "report": {
      "partial_loading": {
        "event_type":
)TF";
    str << type;
    str << R"TF(,
        "report": {
          "loading_events": [{
            "intervals": [
)TF";
    str << interval;
    str << R"TF(],
            "loading_metadata": {
              "compression_level": 100,)TF";
    if (add_group) {
        str << "\"group_id\":" << any_string << ",";
    }
    str << R"TF(
              "network_info": {
                "bandwidth_bps": "1000000000",
                "connectivity": 1
              },
              "source": 5,
              "state": 3
            }
          }]
        }
      }
    }
  }]
}
)TF";
    return str.str();
}

TuningForkLogEvent TestEndToEndWithAbandonedLoadingTimes(bool add_group) {
    const int NTICKS =
        101;  // note the first tick doesn't add anything to the histogram
    const uint64_t kOneGigaBitPerSecond = 1000000000L;
    auto settings =
        TestSettings(tf::Settings::AggregationStrategy::Submission::TICK_BASED,
                     NTICKS - 1, 2, {}, {}, 0 /* use default */, 3);
    TuningForkTest test(settings, milliseconds(10));
    tf::SerializedAnnotation loading_annotation = {1, 2, 3};
    Annotation ann;
    if (add_group) {
        EXPECT_EQ(tf::StartLoadingGroup(nullptr, nullptr, nullptr),
                  TUNINGFORK_ERROR_INVALID_LOADING_STATE);
        TuningFork_LoadingTimeMetadata group_metadata{};
        group_metadata.state = TuningFork_LoadingTimeMetadata::FIRST_RUN;
        EXPECT_EQ(tf::StartLoadingGroup(&group_metadata, nullptr, nullptr),
                  TUNINGFORK_ERROR_OK);
    }
    tf::LoadingHandle loading_handle;
    tf::StartRecordingLoadingTime(
        {tf::LoadingTimeMetadata::LoadingState::WARM_START,
         tf::LoadingTimeMetadata::LoadingSource::NETWORK, 100,
         tf::LoadingTimeMetadata::NetworkConnectivity::WIFI,
         kOneGigaBitPerSecond, 0},
        loading_annotation, loading_handle);
    test.IncrementTime(5);
    {
        std::unique_lock<std::mutex> lock(*test.rmutex_);
        tf::ReportLifecycleEvent(TUNINGFORK_STATE_ONSTOP);
        // Wait for the upload thread to complete writing the string
        EXPECT_TRUE(test.cv_->wait_for(lock, s_test_wait_time) ==
                    std::cv_status::no_timeout)
            << "Timeout";
        auto expected_result = AbandonedLoadingEvent(
            2, "0.05s", "{\"end\": \"0.15s\",\"start\": \"0.1s\"}", add_group);
        CheckStrings("Lifecycle event", test.Result(), expected_result);
        test.ClearResult();
    }
    test.IncrementTime(5);
    {
        std::unique_lock<std::mutex> lock(*test.rmutex_);
        tf::ReportLifecycleEvent(TUNINGFORK_STATE_ONSTART);
        // Wait for the upload thread to complete writing the string
        EXPECT_TRUE(test.cv_->wait_for(lock, s_test_wait_time) ==
                    std::cv_status::no_timeout)
            << "Timeout";
        auto expected_result = AbandonedLoadingEvent(
            1, "0.1s", "{\"end\": \"0.2s\",\"start\": \"0.1s\"}", add_group);
        CheckStrings("Lifecycle event", test.Result(), expected_result);
        test.ClearResult();
    }
    tf::StopRecordingLoadingTime(loading_handle);
    std::unique_lock<std::mutex> lock(*test.rmutex_);
    for (int i = 0; i < NTICKS; ++i) {
        test.IncrementTime();
        ann.set_level(com::google::tuningfork::LEVEL_1);
        tf::SetCurrentAnnotation(tf::Serialize(ann));
        tf::FrameTick(TFTICK_PACED_FRAME_TIME);
    }
    // Wait for the upload thread to complete writing the string
    EXPECT_TRUE(test.cv_->wait_for(lock, s_test_wait_time) ==
                std::cv_status::no_timeout)
        << "Timeout";

    return test.Result();
}

extern TuningForkLogEvent ExpectedResultWithLoading();

TEST(EndToEndTest, WithAbandonedLoadingTimes) {
    auto result = TestEndToEndWithAbandonedLoadingTimes(false /* add_group */);
    CheckStrings("LoadingTimes", result, ExpectedResultWithLoading());
}

extern TuningForkLogEvent ExpectedResultWithLoadingGroups(bool use_stop,
                                                          bool with_annotation);
TEST(EndToEndTest, WithAbandonedLoadingTimesAndGroup) {
    auto result = TestEndToEndWithAbandonedLoadingTimes(true /* add_group */);
    CheckStrings("LoadingTimes", result,
                 ExpectedResultWithLoadingGroups(false, false));
}

}  // namespace tuningfork_test
