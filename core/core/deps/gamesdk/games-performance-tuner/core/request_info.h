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

#include <cstdint>
#include <string>
#include <vector>

#include "meminfo_provider.h"

namespace tuningfork {

// Forward declaration.
class Settings;

// Extra information that is sent with requests including device information,
// game package information and session information.
struct RequestInfo {
  std::string experiment_id;
  std::string session_id;
  std::string previous_session_id;
  uint64_t total_memory_bytes;
  uint32_t gl_es_version;
  std::string build_fingerprint;
  std::string build_version_sdk;
  std::vector<uint64_t> cpu_max_freq_hz;
  std::string apk_package_name;
  uint32_t apk_version_code;
  uint32_t tuningfork_version;
  std::string model;
  std::string brand;
  std::string product;
  std::string device;
  std::string soc_model;
  std::string soc_manufacturer;
  int64_t swap_total_bytes;
  uint32_t swappy_version;
  int32_t height_pixels;
  int32_t width_pixels;

  // Note that this will include an empty experiment_id
  static RequestInfo ForThisGameAndDevice(const Settings& settings);

  // We have a globally accessible cached value
  static RequestInfo& CachedValue();

  void UpdateMemoryValues(IMemInfoProvider* meminfo_provider);
};

}  // namespace tuningfork
