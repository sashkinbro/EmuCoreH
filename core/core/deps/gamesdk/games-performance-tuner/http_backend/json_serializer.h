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

#include <json11/json11.hpp>
#include <string>
#include <vector>

#include "core/id_provider.h"
#include "core/lifecycle_upload_event.h"
#include "core/session.h"

namespace tuningfork {

class JsonSerializer {
 public:
  JsonSerializer(const Session& session, IdProvider* id_provider)
      : session_(session), id_provider_(id_provider) {}

  void SerializeEvent(const RequestInfo& device_info,
                      std::string& evt_json_ser);

  void SerializeLifecycleEvent(const LifecycleUploadEvent& event,
                               const RequestInfo& request_info,
                               std::string& evt_json_ser);

  static TuningFork_ErrorCode DeserializeAndMerge(
      const std::string& evt_json_ser, IdProvider& id_provider,
      Session& session);

  // Utility function: string representation of a double using fixed-point
  // notation to 9 decimal point precision.
  static std::string FixedAndTruncated(double d);

 private:
  json11::Json::object TelemetryContextJson(const AnnotationId& annotation,
                                            const RequestInfo& request_info,
                                            const Duration& duration);

  json11::Json::object TelemetryReportJson(const AnnotationId& annotation,
                                           bool& empty, Duration& duration);

  json11::Json::object PartialLoadingTelemetryReportJson(
      const AnnotationId& annotation, const LifecycleUploadEvent& event,
      Duration& duration);

  json11::Json::object TelemetryJson(const AnnotationId& annotation,
                                     const RequestInfo& request_info,
                                     Duration& duration, bool& empty);

  json11::Json::object PartialLoadingTelemetryJson(
      const AnnotationId& annotation, const LifecycleUploadEvent& event,
      const RequestInfo& request_info);

  json11::Json::object LoadingTimeMetadataJson(
      const LoadingTimeMetadataWithGroup& md);
  std::vector<json11::Json::object> CrashReportsJson(
      const RequestInfo& request_info);

  void SerializeTelemetryRequest(
      const RequestInfo& request_info,
      const std::vector<json11::Json::object>& telemetry,
      std::string& evt_json_ser);

  const Session& session_;
  IdProvider* id_provider_;
};

}  // namespace tuningfork
