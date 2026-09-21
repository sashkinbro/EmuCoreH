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

class TestBackend : public tf::IBackend {
 public:
  TestBackend(
      std::shared_ptr<std::condition_variable> cv_,
      std::shared_ptr<std::mutex> mutex_,
      std::shared_ptr<IBackend> dl_backend_ = std::shared_ptr<IBackend>())
      : cv(cv_), mutex(mutex_), dl_backend(dl_backend_) {}

  TuningFork_ErrorCode UploadTelemetry(
      const TuningForkLogEvent& evt_ser) override {
    ALOGI("Process");
    {
      std::lock_guard<std::mutex> lock(*mutex);
      result = evt_ser;
    }
    cv->notify_all();
    return TUNINGFORK_ERROR_OK;
  }

  TuningFork_ErrorCode GenerateTuningParameters(
      tf::HttpRequest& request,
      const tf::ProtobufSerialization* training_mode_params,
      tf::ProtobufSerialization& fidelity_params,
      std::string& experiment_id) override {
    if (dl_backend) {
      return dl_backend->GenerateTuningParameters(
          request, training_mode_params, fidelity_params, experiment_id);
    } else {
      return TUNINGFORK_ERROR_OK;
    }
  }

  TuningFork_ErrorCode PredictQualityLevels(
      tf::HttpRequest& request, tf::QLTimePredictions& predictions) override {
    return TUNINGFORK_ERROR_OK;
  }

  TuningFork_ErrorCode UploadDebugInfo(tf::HttpRequest& request) override {
    return TUNINGFORK_ERROR_OK;
  }

  void Stop() override {}

  void Clear() { result = ""; }

  void SetDownloadBackend(const std::shared_ptr<tf::IBackend>& dl) {
    dl_backend = dl;
  }

  TuningForkLogEvent result;
  std::shared_ptr<std::condition_variable> cv;
  std::shared_ptr<std::mutex> mutex;
  std::shared_ptr<IBackend> dl_backend;
};

}  // namespace tuningfork_test
