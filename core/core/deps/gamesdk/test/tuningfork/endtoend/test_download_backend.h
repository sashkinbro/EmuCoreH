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

#pragma once

#include "common.h"

namespace tuningfork_test {

class TestDownloadBackend : public tf::IBackend {
 public:
  int n_times_called_ = 0;
  int wait_count_ = 0;
  const tf::ProtobufSerialization* download_params_;
  const tf::ProtobufSerialization* expected_training_params_;
  TestDownloadBackend(
      int wait_count = 0,
      const tf::ProtobufSerialization* download_params = nullptr,
      const tf::ProtobufSerialization* expected_training_params = nullptr)
      : wait_count_(wait_count),
        download_params_(download_params),
        expected_training_params_(expected_training_params) {}

  TuningFork_ErrorCode GenerateTuningParameters(
      tf::HttpRequest& request,
      const tf::ProtobufSerialization* training_mode_params,
      tf::ProtobufSerialization& fidelity_params,
      std::string& experiment_id) override {
    n_times_called_++;
    if (expected_training_params_ != nullptr) {
      EXPECT_NE(training_mode_params, nullptr);
      EXPECT_EQ(*expected_training_params_, *training_mode_params);
    } else {
      EXPECT_EQ(training_mode_params, nullptr);
    }
    if (n_times_called_ > wait_count_) {
      if (download_params_ != nullptr) {
        fidelity_params = *download_params_;
        return TUNINGFORK_ERROR_OK;
      } else {
        return TUNINGFORK_ERROR_NO_FIDELITY_PARAMS;
      }
    } else {
      return TUNINGFORK_ERROR_GENERATE_TUNING_PARAMETERS_ERROR;
    }
  }

  TuningFork_ErrorCode PredictQualityLevels(
      tf::HttpRequest& request, tf::QLTimePredictions& predictions) override {
    return TUNINGFORK_ERROR_OK;
  }

  TuningFork_ErrorCode UploadTelemetry(
      const TuningForkLogEvent& evt_ser) override {
    return TUNINGFORK_ERROR_OK;
  }

  TuningFork_ErrorCode UploadDebugInfo(tf::HttpRequest& request) override {
    return TUNINGFORK_ERROR_OK;
  }

  void Stop() override {}
};

}  // namespace tuningfork_test
