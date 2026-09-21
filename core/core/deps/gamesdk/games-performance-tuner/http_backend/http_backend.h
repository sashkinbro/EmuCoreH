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

#pragma once

#include <jni.h>

#include <memory>
#include <sstream>
#include <string>

#include "core/tuningfork_internal.h"
#include "http_request.h"

namespace tuningfork {

class UltimateUploader;

// Google Endpoint backend
class HttpBackend : public IBackend {
 public:
  TuningFork_ErrorCode Init(const Settings& settings);
  ~HttpBackend() override;

  // Perform a blocking call to get fidelity parameters from the server.
  TuningFork_ErrorCode GenerateTuningParameters(
      HttpRequest& request, const ProtobufSerialization* training_mode_fps,
      ProtobufSerialization& fidelity_params,
      std::string& experiment_id) override;

  // Perform a blocking call to upload telemetry info to a server.
  virtual TuningFork_ErrorCode UploadTelemetry(
      const std::string& tuningfork_log_event) override;

  // Perform a blocking call to upload debug info to a server.
  virtual TuningFork_ErrorCode UploadDebugInfo(HttpRequest& request) override;

  virtual void Stop() override;

 private:
  std::shared_ptr<UltimateUploader> ultimate_uploader_;
  const TuningFork_Cache* persister_ = nullptr;
};

}  // namespace tuningfork
