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

#include <map>
#include <memory>
#include <regex>
#include <string>

#include "jni/jni_wrap.h"
#include "json11/json11.hpp"

#define LOG_TAG "MemoryAdvice"
#include "Log.h"

using namespace gamesdk::jni;

namespace memory_advice {

using namespace json11;

/**
 * @brief Provides memory info from various metrics
 */
class IMetricsProvider {
 public:
  typedef Json::object (IMetricsProvider::*MetricsFunction)();
  /** @brief A map matching metrics category names to their functions */
  std::map<std::string, MetricsFunction> metrics_categories_ = {
      {"meminfo", &IMetricsProvider::GetMeminfoValues},
      {"status", &IMetricsProvider::GetStatusValues},
      {"proc", &IMetricsProvider::GetProcValues},
      {"debug", &IMetricsProvider::GetDebugValues},
      {"MemoryInfo", &IMetricsProvider::GetActivityManagerMemoryInfo},
      {"ActivityManager", &IMetricsProvider::GetActivityManagerValues}};
  /** @brief Get a list of memory metrics stored in /proc/meminfo */
  virtual Json::object GetMeminfoValues() = 0;
  /** @brief Get a list of memory metrics stored in /proc/{pid}/status */
  virtual Json::object GetStatusValues() = 0;
  /**
   * @brief Get a list of various memory metrics stored in /proc/{pid}
   * folder.
   */
  virtual Json::object GetProcValues() = 0;
  /**
   * @brief Get a list of memory metrics available from ActivityManager
   */
  virtual Json::object GetActivityManagerValues() = 0;
  /**
   * @brief Get a list of memory metrics available from
   * ActivityManager#getMemoryInfo().
   */
  virtual Json::object GetActivityManagerMemoryInfo() = 0;
  /**
   * @brief Get a list of memory metrics available from android.os.Debug
   */
  virtual Json::object GetDebugValues() = 0;

  virtual ~IMetricsProvider() {}
};

// Implementation that reads files from /proc/ and uses JNI calls to
// determine memory values
class DefaultMetricsProvider : public IMetricsProvider {
 public:
  Json::object GetMeminfoValues() override;
  Json::object GetStatusValues() override;
  Json::object GetProcValues() override;
  Json::object GetActivityManagerValues() override;
  Json::object GetActivityManagerMemoryInfo() override;
  Json::object GetDebugValues() override;

 private:
  android::os::DebugClass android_debug_;
  /**
   * @brief Reads the given file and dumps the memory values within as a map
   */
  Json::object GetMemoryValuesFromFile(const std::string &path,
                                       const std::regex &pattern);
  /** @brief Reads the OOM Score of the app from /proc/{pid}/oom_score */
  int32_t GetOomScore();
};

}  // namespace memory_advice
