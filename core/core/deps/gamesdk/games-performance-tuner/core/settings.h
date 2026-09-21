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
#include <vector>

#include "core/common.h"

namespace tuningfork {

// Internal representation of TuningFork settings, including the C settings
// passed to Init
//  and the settings loaded from the tuningfork_settings.bin file.
struct Settings {
  struct Histogram {
    int32_t instrument_key;
    float bucket_min;
    float bucket_max;
    int32_t n_buckets;
  };
  struct AggregationStrategy {
    enum class Submission { TICK_BASED, TIME_BASED };
    Submission method;
    uint32_t intervalms_or_count;
    uint32_t max_instrumentation_keys;
    std::vector<uint32_t> annotation_enum_size;
  };
  TuningFork_Settings c_settings;
  AggregationStrategy aggregation_strategy;
  std::vector<Histogram> histograms;
  std::string base_uri;
  std::string api_key;
  std::string default_fidelity_parameters_filename;
  uint32_t initial_request_timeout_ms;
  uint32_t ultimate_request_timeout_ms;
  int32_t loading_annotation_index;
  int32_t level_annotation_index;

  std::string EndpointUri() const {
    std::string uri;
    if (c_settings.endpoint_uri_override == nullptr)
      uri = base_uri;
    else
      uri = c_settings.endpoint_uri_override;
    if (!uri.empty() && uri.back() != '/') uri += '/';
    return uri;
  }

  // Check validity of the settings, filling in defaults where there are
  // missing values, and use save_dir to initialize the persister if it's not
  // already set in c_settings or the default if save_dir is empty.
  void Check(const std::string& save_dir = "");

  uint64_t NumAnnotationCombinations();

  // The default histogram that is used if the user doesn't specify one in
  // Settings
  static Settings::Histogram DefaultHistogram(InstrumentationKey ikey);

  // Load settings from assets/tuningfork/tuningfork_settings.bin.
  // Ownership of @p settings is passed to the caller: call
  //  TuningFork_Settings_Free to deallocate data stored in the struct.
  // Returns TUNINGFORK_ERROR_OK and fills 'settings' if the file could be
  // loaded. Returns TUNINGFORK_ERROR_NO_SETTINGS if the file was not found.
  static TuningFork_ErrorCode FindInApk(Settings* settings);

  // Deserialize settings from a com.google.tuningfork.Settings proto to a
  // tuningfork::Settings structure. Overriding values from c_settings is
  // handled here too.
  static TuningFork_ErrorCode DeserializeSettings(
      const ProtobufSerialization& settings_ser, Settings* settings);
};

}  // namespace tuningfork
