/*
 * Copyright 2020 The Android Open Source Project
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

#include <string>

#include "http_backend/http_request.h"
#include "proto/protobuf_util.h"
#include "tuningfork/tuningfork.h"

namespace tuningfork {

// Constants used by the upload threads for coordinating persisting and
// uploading histograms.
const uint64_t HISTOGRAMS_PAUSED = 0;
const uint64_t HISTOGRAMS_UPLOADING = 1;

// Interface for download and upload of information from Tuning Fork.
class IBackend {
 public:
  virtual ~IBackend() {};

  // Perform a blocking call to get fidelity parameters from the server.
  virtual TuningFork_ErrorCode GenerateTuningParameters(
      HttpRequest& request, const ProtobufSerialization* training_mode_fps,
      ProtobufSerialization& fidelity_params, std::string& experiment_id) = 0;

  // Perform a blocking call to upload telemetry info to a server.
  virtual TuningFork_ErrorCode UploadTelemetry(
      const std::string& tuningfork_log_event) = 0;

  // Perform a blocking call to upload debug info to a server.
  virtual TuningFork_ErrorCode UploadDebugInfo(HttpRequest& request) = 0;

  // Tell all threads to complete activity and wait for all current work to
  // finish.
  virtual void Stop() = 0;
};

}  // namespace tuningfork
