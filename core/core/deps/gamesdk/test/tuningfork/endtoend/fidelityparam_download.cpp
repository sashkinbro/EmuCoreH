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

static std::condition_variable fp_cv;
static std::mutex fp_mutex;
static int fp_n_callbacks_called = 0;
tf::ProtobufSerialization default_fps = {1, 2, 3};
void FidelityParamsCallback(const TuningFork_CProtobufSerialization* s) {
    EXPECT_NE(s, nullptr) << "Null FPs in callback";
    auto in_fps = tf::ToProtobufSerialization(*s);
    EXPECT_EQ(in_fps, default_fps) << "Wrong FPs in callback";
    fp_n_callbacks_called++;
    fp_cv.notify_all();
}

void TestFidelityParamDownloadThread(bool insights) {
    TuningFork_CProtobufSerialization c_default_fps;
    tf::ToCProtobufSerialization(default_fps, c_default_fps);

    auto settings = TestSettings(
        tf::Settings::AggregationStrategy::Submission::TICK_BASED, 100, 1, {});

    settings.api_key = "dummy_api_key";
    settings.c_settings.training_fidelity_params = nullptr;

    auto download_backend = insights ? std::make_shared<TestDownloadBackend>(
                                           /*wait_count*/ 0, nullptr)
                                     : std::make_shared<TestDownloadBackend>(
                                           /*wait_count*/ 3, &default_fps);

    TuningForkTest test(settings, milliseconds(20), download_backend);

    auto r = TuningFork_startFidelityParamDownloadThread(
        nullptr, FidelityParamsCallback);
    EXPECT_EQ(r, TUNINGFORK_ERROR_BAD_PARAMETER);
    std::unique_lock<std::mutex> lock(fp_mutex);
    r = TuningFork_startFidelityParamDownloadThread(&c_default_fps,
                                                    FidelityParamsCallback);
    EXPECT_EQ(r, TUNINGFORK_ERROR_OK);

    fp_n_callbacks_called = 0;

    if (insights) {
        EXPECT_TRUE(fp_cv.wait_for(lock, s_test_wait_time) !=
                    std::cv_status::timeout)
            << "Timeout";
        EXPECT_EQ(fp_n_callbacks_called, 1);
        EXPECT_EQ(download_backend->n_times_called_, 1);
    } else {
        // Wait for the download thread. We have one initial callback with the
        // defaults and
        //  one with the ones from the loader.
        for (int i = 0; i < 2; ++i) {
            EXPECT_TRUE(fp_cv.wait_for(lock, s_test_wait_time) !=
                        std::cv_status::timeout)
                << "Timeout";
        }
        EXPECT_EQ(fp_n_callbacks_called, 2);
        EXPECT_EQ(download_backend->n_times_called_, 4);
    }

    TuningFork_CProtobufSerialization_free(&c_default_fps);
}

TEST(EndToEndTest, FidelityParamDownloadThread) {
    TestFidelityParamDownloadThread(/*insights*/ true);
    TestFidelityParamDownloadThread(/*insights*/ false);
}

struct TestResponse {
    std::string request;
    int response_code;
    std::string response_body;
};

class TestRequest : public tf::HttpRequest {
    std::vector<TestResponse> responses_;
    int next_response_ = 0;

   public:
    TestRequest(const HttpRequest& r, std::vector<TestResponse> responses)
        : HttpRequest(r), responses_(responses) {}
    TuningFork_ErrorCode Send(const std::string& rpc_name,
                              const std::string& request, int& response_code,
                              std::string& response_body) override {
        EXPECT_LT(next_response_, responses_.size()) << "Unexpected request";
        if (next_response_ < responses_.size()) {
            auto& expected = responses_[next_response_];
            if (CheckStrings("DownloadRequest", request, expected.request)) {
                response_code = expected.response_code;
                response_body = expected.response_body;
                ++next_response_;
            }
        }
        return TUNINGFORK_ERROR_OK;
    }
};

static const std::string empty_tuning_parameters_request = R"({
  "device_spec": {
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
  "name": "applications//apks/0"
})";

TEST(EndToEndTest, FidelityParamDownloadRequest) {
    tf::HttpBackend backend;
    tf::HttpRequest inner_request("https://test.google.com", "dummy_api_key",
                                  milliseconds(1000));
    TestRequest request(inner_request,
                        {{empty_tuning_parameters_request, 200, "out"}});
    tf::ProtobufSerialization fps;
    std::string experiment_id;
    backend.GenerateTuningParameters(request, nullptr, fps, experiment_id);
}

}  // namespace tuningfork_test
