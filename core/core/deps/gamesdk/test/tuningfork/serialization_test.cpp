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

#include <gtest/gtest.h>

#include <chrono>

#include "common/gamesdk_common.h"
#include "core/tuningfork_utils.h"
#include "http_backend/json_serializer.h"
#include "test_utils.h"

namespace serialization_test {

using namespace tuningfork;
using namespace gamesdk_test;
using namespace json11;
using namespace std::chrono;

RequestInfo test_device_info{
    "expt" /*experiment_id*/,
    {} /*current_fidelity_parameters*/,
    "sess" /*session_id*/,
    "prev_sess" /*previous_session_id*/,
    2387 /*total_memory_bytes*/,
    349587 /*gl_es_version*/,
    "fing" /*build_fingerprint*/,
    "6.3" /*build_version_sdk*/,
    {1, 2, 3} /*cpu_max_freq_hz*/,
    "packname" /*apk_package_name*/,
    0 /*apk_version_code*/,
    ANDROID_GAMESDK_PACKED_VERSION(0, 10, 0) /*tuningfork_version*/,
    "MODEL" /*model*/,
    "BRAND" /*brand*/,
    "PRODUCT" /*product*/,
    "DEVICE" /*device*/,
    "SOC_MODEL" /*soc_model*/,
    "SOC_MANUFACTURER" /*soc_manufacturer*/,
    234 /*swap_total_bytes*/,
    ANDROID_GAMESDK_PACKED_VERSION(2, 7, 0) /*swappy_version*/,
    1024 /*height_pixels*/,
    768 /*width_pixels*/};

std::string test_device_info_ser = R"TF({
  "brand": "BRAND",
  "build_version": "6.3",
  "cpu_core_freqs_hz": [1, 2, 3],
  "device": "DEVICE",
  "fingerprint": "fing",
  "gles_version": {
    "major": 5, "minor": 21907
  },
  "height_pixels": 1024,
  "model": "MODEL",
  "product": "PRODUCT",
  "soc_manufacturer": "SOC_MANUFACTURER",
  "soc_model": "SOC_MODEL",
  "swap_total_bytes": 234,
  "total_memory_bytes": 2387,
  "width_pixels": 768
})TF";

void CheckDeviceInfo(const RequestInfo& info) {
    EXPECT_EQ(json_utils::GetResourceName(info), "applications/packname/apks/0")
        << "GetResourceName";
    EXPECT_TRUE(CompareIgnoringWhitespace(
        Json(json_utils::DeviceSpecJson(info)).dump(), test_device_info_ser))
        << "DeviceSpecJson";
}

TEST(SerializationTest, DeviceInfo) { CheckDeviceInfo(test_device_info); }

std::string report_start = R"TF({
  "name": "applications/packname/apks/0",
  "session_context": {
    "device": {
      "brand": "BRAND",
      "build_version": "6.3",
      "cpu_core_freqs_hz": [1, 2, 3],
      "device": "DEVICE",
      "fingerprint": "fing",
      "gles_version": {
        "major": 5,
        "minor": 21907
      },
      "height_pixels": 1024,
      "model": "MODEL",
      "product": "PRODUCT",
      "soc_manufacturer": "SOC_MANUFACTURER",
      "soc_model": "SOC_MODEL",
      "swap_total_bytes": 234,
      "total_memory_bytes": 2387,
      "width_pixels": 768
    },
    "game_sdk_info": {
      "session_id": "sess",
      "swappy_version": "2.7.0",
      "version": "0.10.0"
    },
    "time_period": {
      "end_time": "1970-01-01T00:00:00.000000Z",
      "start_time": "1970-01-01T00:00:00.000000Z"
    }
  },
  "telemetry": [)TF";
std::string report_end = "]}";

std::string single_tick_with_loading = R"TF({
  "context": {
    "annotations": "AQID",
    "duration": "1.5s",
    "tuning_parameters": {
      "experiment_id": "expt",
      "serialized_fidelity_parameters": ""
    }
  },
  "report": {
    "loading": {
      "loading_events": [{
        "loading_metadata": {
          "group_id": "ABC",
          "network_info": {
            "bandwidth_bps": "1000000000",
            "connectivity": 1,
            "latency": "0.05s"
          },
          "source":5,
          "state":1
        },
        "times_ms": [1500]
      }]
    },
    "rendering": {
      "render_time_histogram": [{"counts": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                            0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
      "instrument_id": 1234
      }]
    }
  }
})TF";

class IdMap : public IdProvider {
    TuningFork_ErrorCode SerializedAnnotationToAnnotationId(
        const ProtobufSerialization& ser, AnnotationId& id) override {
        id = 0;
        return TUNINGFORK_ERROR_OK;
    }

    // Return a new id that is made up of <annotation_id> and <k>.
    // Gives an error if the id is out-of-bounds.
    TuningFork_ErrorCode MakeCompoundId(InstrumentationKey k,
                                        AnnotationId annotation_id,
                                        MetricId& id) override {
        id = MetricId::FrameTime(annotation_id, k);
        return TUNINGFORK_ERROR_OK;
    }

    TuningFork_ErrorCode AnnotationIdToSerializedAnnotation(
        AnnotationId id, SerializedAnnotation& ann) override {
        ann = {1, 2, 3};
        return TUNINGFORK_ERROR_OK;
    }
    TuningFork_ErrorCode MetricIdToLoadingTimeMetadata(
        MetricId id, LoadingTimeMetadataWithGroup& mg) override {
        LoadingTimeMetadata& m = mg.metadata;
        m = {};
        m.state = LoadingTimeMetadata::FIRST_RUN;
        m.source = LoadingTimeMetadata::NETWORK;
        m.network_latency_ns = 50000000;  // 50ms
        m.network_connectivity = LoadingTimeMetadata::NetworkConnectivity::WIFI;
        m.network_transfer_speed_bps = 1000000000;  // 1Gb/s
        mg.group_id = "ABC";
        return TUNINGFORK_ERROR_OK;
    }
};

TEST(SerializationTest, SerializationWithLoading) {
    Session session{};
    MetricId loading_time_metric = MetricId::LoadingTime(0, 0);
    MetricId frame_time_metric = MetricId::FrameTime(0, 0);
    session.CreateLoadingTimeSeries(loading_time_metric);
    session.CreateFrameTimeHistogram(frame_time_metric,
                                     Settings::DefaultHistogram(1));

    std::string evt_ser;
    session.SetInstrumentationKeys({1234});
    IdMap metric_map;
    JsonSerializer serializer(session, &metric_map);
    serializer.SerializeEvent(test_device_info, evt_ser);
    auto empty_report = report_start + report_end;
    EXPECT_TRUE(CompareIgnoringWhitespace(evt_ser, empty_report))
        << evt_ser << "\n!=\n"
        << empty_report;

    // Fill in some data
    auto p1 = session.GetData<LoadingTimeMetricData>(loading_time_metric);
    ASSERT_NE(p1, nullptr);
    p1->Record(milliseconds(1500));

    auto p2 = session.GetData<FrameTimeMetricData>(frame_time_metric);
    ASSERT_NE(p2, nullptr);
    p2->Record(milliseconds(10));

    // Try to record with some LoadingTimeMetadata - this will fail because we
    // didn't allocate any extra loading time space.
    MetricId new_loading_time_metric = MetricId::LoadingTime(0, 1);
    EXPECT_EQ(session.GetData<LoadingTimeMetricData>(new_loading_time_metric),
              nullptr);

    serializer.SerializeEvent(test_device_info, evt_ser);
    auto report = report_start + single_tick_with_loading + report_end;
    EXPECT_TRUE(CompareIgnoringWhitespace(evt_ser, report))
        << evt_ser << "\n!=\n"
        << report;
}

std::string single_tick = R"TF({
  "context": {
    "annotations": "AQID",
    "duration": "0.03s",
    "tuning_parameters": {
      "experiment_id": "expt",
      "serialized_fidelity_parameters": ""
    }
  },
  "report": {
    "rendering": {
      "render_time_histogram": [{
        "counts": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                   0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
        "instrument_id": 0
      }]
    }
  }
})TF";

void CheckSessions(Session& pc0, Session& pc1) {
    auto p0 = pc0.GetData<FrameTimeMetricData>(MetricId::FrameTime(0, 0));
    auto p1 = pc1.GetData<FrameTimeMetricData>(MetricId::FrameTime(0, 0));
    EXPECT_TRUE(p0 != nullptr && p1 != nullptr);
    EXPECT_EQ(p0->histogram_, p1->histogram_);
}

Settings::Histogram DefaultHistogram() { return {-1, 10, 40, 30}; }

TEST(SerializationTest, GEDeserialization) {
    Session session{};
    FrameTimeMetric metric(0);
    MetricId metric_id{0};
    session.CreateFrameTimeHistogram(metric_id, DefaultHistogram());
    std::string evt_ser;
    IdMap metric_map;
    JsonSerializer serializer(session, &metric_map);
    serializer.SerializeEvent(test_device_info, evt_ser);
    auto empty_report = report_start + report_end;
    EXPECT_TRUE(CompareIgnoringWhitespace(evt_ser, empty_report))
        << evt_ser << "\n!=\n"
        << empty_report;
    // Fill in some data
    auto p = session.GetData<FrameTimeMetricData>(metric_id);
    p->Record(milliseconds(30));
    serializer.SerializeEvent(test_device_info, evt_ser);
    auto report = report_start + single_tick + report_end;
    EXPECT_TRUE(CompareIgnoringWhitespace(evt_ser, report))
        << evt_ser << "\n!=\n"
        << report;
    Session session1{};
    session1.CreateFrameTimeHistogram(metric_id, DefaultHistogram());
    EXPECT_EQ(
        JsonSerializer::DeserializeAndMerge(evt_ser, metric_map, session1),
        TUNINGFORK_ERROR_OK)
        << "Deserialize single";
    CheckSessions(session1, session);
}

TEST(SerializationTest, DurationSerialization) {
    std::vector<double> ds = {1e19, 1e15, 1e10, 1e5,  1e0,   1e-1,
                              1e-3, 1e-5, 1e-8, 1e-9, 1e-10, 1e-15};
    std::vector<std::string> results = {"10000000000000000000",
                                        "1000000000000000",
                                        "10000000000",
                                        "100000",
                                        "1",
                                        "0.1",
                                        "0.001",
                                        "0.00001",
                                        "0.00000001",
                                        "0.000000001",
                                        "0",
                                        "0"};
    EXPECT_EQ(ds.size(), results.size()) << "Test set up badly";
    for (int i = 0; i < std::min(ds.size(), results.size()); ++i) {
        std::stringstream str;
        str << JsonSerializer::FixedAndTruncated(ds[i]);
        EXPECT_EQ(str.str(), results[i])
            << "expected " << results[i] << " got " << str.str();
    }
}

}  // namespace serialization_test
