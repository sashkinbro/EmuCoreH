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

TuningForkLogEvent TestEndToEndWithLoadingGroups(
    bool use_stop, const tf::ProtobufSerialization* group_annotation) {
    const int NTICKS =
        101;  // note the first tick doesn't add anything to the histogram
    const uint64_t kOneGigaBitPerSecond = 1000000000L;
    auto settings =
        TestSettings(tf::Settings::AggregationStrategy::Submission::TICK_BASED,
                     NTICKS - 1, 2, {}, {}, 0 /* use default */, 4);
    TuningForkTest test(settings, milliseconds(10));
    tf::SerializedAnnotation loading_annotation = {1, 2, 3};
    Annotation ann;
    tf::LoadingHandle group_handle = 0;
    TuningFork_LoadingTimeMetadata metadata{};
    EXPECT_EQ(tf::StartLoadingGroup(nullptr, group_annotation,
                                    use_stop ? &group_handle : nullptr),
              TUNINGFORK_ERROR_INVALID_LOADING_STATE);
    metadata.state = TuningFork_LoadingTimeMetadata::HOT_START;
    EXPECT_EQ(tf::StartLoadingGroup(&metadata, group_annotation,
                                    use_stop ? &group_handle : nullptr),
              TUNINGFORK_ERROR_OK);
    tf::LoadingHandle loading_handle;
    tf::StartRecordingLoadingTime(
        {tf::LoadingTimeMetadata::LoadingState::WARM_START,
         tf::LoadingTimeMetadata::LoadingSource::NETWORK, 100,
         tf::LoadingTimeMetadata::NetworkConnectivity::WIFI,
         kOneGigaBitPerSecond, 0},
        loading_annotation, loading_handle);
    test.IncrementTime(10);
    tf::StopRecordingLoadingTime(loading_handle);
    if (use_stop) tf::StopLoadingGroup(group_handle);
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

TuningForkLogEvent ExpectedResultWithLoadingGroups(bool use_stop,
                                                   bool with_annotation) {
    std::string any_string = "\"!REGEX([^\"]*)\"";
    std::string first_duration = "0.21s";
    // An event generated because of the StopRecordingGroup call.
    std::string extra_event;
    if (use_stop)
        extra_event += R"TF(
            {
              "intervals":[{"end":"0.2s", "start":"0.1s"}],
              "loading_metadata":{
                "group_id": )TF" +
                       any_string + R"TF(,
                "source":9,
                "state":4
              }
            })TF";
    // An event in the first batch of events because of the StopRecordingGroup
    // call without an annotation.
    std::string first_extra_event =
        (with_annotation || !use_stop) ? "" : (extra_event + ",");
    // A report generated because we used an annotation with the group.
    std::string extra_report = with_annotation ? R"TF(
    {
      "context":{
        "annotations": "AQIDBA==",
        "duration": "0.1s",
        "tuning_parameters":{
          "experiment_id": "",
          "serialized_fidelity_parameters": ""
        }
      },
      "report":{
        "loading":{
          "loading_events": [)TF" + extra_event + R"TF(]
        }
      }
    },
)TF"
                                               : "";
    return R"TF(
{
  "name": "applications//apks/0",
  "session_context":)TF" +
           session_context_loading + R"TF(,
  "telemetry":[
    {
      "context":{
        "annotations":"",
        "duration":")TF" +
           first_duration + R"TF(",
        "tuning_parameters":{
          "experiment_id":"",
          "serialized_fidelity_parameters":""
        }
      },
      "report":{
        "loading":{
          "loading_events":[
            {
              "intervals":[{"end":"0.21s", "start":"0s"}],
              "loading_metadata":{
                "source":8,
                "state":2
              }
            },
)TF" + first_extra_event +
           R"TF(
            {
              "intervals":[{"end":"0.1s", "start":"0s"}],
              "loading_metadata":{
                "source":7,
                "state":2
              }
            }
          ]
        }
      }
    },
    {
      "context":{
        "annotations": "AQID",
        "duration": "0.1s",
        "tuning_parameters":{
          "experiment_id": "",
          "serialized_fidelity_parameters": ""
        }
      },
      "report":{
        "loading":{
          "loading_events": [{
            "intervals":[{"end":"0.2s", "start":"0.1s"}],
            "loading_metadata": {
              "compression_level": 100,
              "group_id":)TF" +
           any_string + R"TF(,
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
    },
)TF" + extra_report +
           R"TF(
    {
      "context":{
        "annotations":"CAE=",
        "duration":"1s",
        "tuning_parameters":{
          "experiment_id":"",
          "serialized_fidelity_parameters":""
        }
      },
      "report":{
        "rendering":{
          "render_time_histogram":[
            {
              "counts":[**],
              "instrument_id":64001
            }
          ]
        }
      }
    }
  ]
}
)TF";
}

TEST(EndToEndTest, WithLoadingGroups) {
    bool use_stop_call = false;
    bool with_annotation = false;
    auto result = TestEndToEndWithLoadingGroups(use_stop_call, nullptr);
    CheckStrings(
        "LoadingTimes", result,
        ExpectedResultWithLoadingGroups(use_stop_call, with_annotation));
}

TEST(EndToEndTest, WithLoadingGroupsIncStop) {
    bool use_stop_call = true;
    bool with_annotation = false;
    auto result = TestEndToEndWithLoadingGroups(use_stop_call, nullptr);
    CheckStrings(
        "LoadingTimes", result,
        ExpectedResultWithLoadingGroups(use_stop_call, with_annotation));
}

TEST(EndToEndTest, WithLoadingGroupsWithAnnotation) {
    bool use_stop_call = true;
    bool with_annotation = true;
    tf::ProtobufSerialization annotation = {1, 2, 3, 4};
    auto result = TestEndToEndWithLoadingGroups(use_stop_call, &annotation);
    CheckStrings(
        "LoadingTimes", result,
        ExpectedResultWithLoadingGroups(use_stop_call, with_annotation));
}

}  // namespace tuningfork_test
