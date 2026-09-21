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

#include "metrics_provider.h"

#include <stdlib.h>
#include <unistd.h>

#include <chrono>
#include <fstream>
#include <iterator>
#include <map>
#include <regex>
#include <sstream>
#include <streambuf>
#include <utility>
#include <vector>

#include "jni/jni_wrap.h"

using namespace gamesdk::jni;

constexpr double BYTES_IN_KB = 1024;
constexpr double BYTES_IN_MB = 1024 * 1024;
const std::regex MEMINFO_REGEX("([^:]+)[^\\d]*(\\d+).*\n");
const std::regex STATUS_REGEX("([a-zA-Z]+)[^\\d]*(\\d+) kB.*\n");

namespace memory_advice {

using namespace json11;

Json::object DefaultMetricsProvider::GetMeminfoValues() {
    return GetMemoryValuesFromFile("/proc/meminfo", MEMINFO_REGEX);
}

Json::object DefaultMetricsProvider::GetStatusValues() {
    std::stringstream ss_path;
    ss_path << "/proc/" << getpid() << "/status";
    return GetMemoryValuesFromFile(ss_path.str(), STATUS_REGEX);
}

Json::object DefaultMetricsProvider::GetProcValues() {
    Json::object proc_map;
    proc_map["oom_score"] = Json(GetOomScore());
    return proc_map;
}

Json::object DefaultMetricsProvider::GetActivityManagerValues() {
    Json::object metrics_map;
    java::Object obj = AppContext().getSystemService(
        android::content::Context::ACTIVITY_SERVICE);
    android::app::ActivityManager activity_manager(std::move(obj));

    metrics_map["MemoryClass"] =
        Json(activity_manager.getMemoryClass() * BYTES_IN_MB);
    metrics_map["LargeMemoryClass"] =
        Json(activity_manager.getLargeMemoryClass() * BYTES_IN_MB);
    metrics_map["LowRamDevice"] = Json(activity_manager.isLowRamDevice());

    return metrics_map;
}

Json::object DefaultMetricsProvider::GetActivityManagerMemoryInfo() {
    android::app::MemoryInfo memory_info;
    Json::object metrics_map;
    java::Object obj = AppContext().getSystemService(
        android::content::Context::ACTIVITY_SERVICE);
    android::app::ActivityManager activity_manager(std::move(obj));
    activity_manager.getMemoryInfo(memory_info);
    metrics_map["threshold"] = Json((double)memory_info.threshold());
    metrics_map["availMem"] = Json((double)memory_info.availMem());
    metrics_map["totalMem"] = Json((double)memory_info.totalMem());
    metrics_map["lowMemory"] = Json(memory_info.lowMemory());

    return metrics_map;
}

Json::object DefaultMetricsProvider::GetDebugValues() {
    Json::object metrics_map;
    metrics_map["nativeHeapAllocatedSize"] =
        Json((double)android_debug_.getNativeHeapAllocatedSize());
    metrics_map["nativeHeapFreeSize"] =
        Json((double)android_debug_.getNativeHeapFreeSize());
    metrics_map["nativeHeapSize"] =
        Json((double)android_debug_.getNativeHeapSize());

    return metrics_map;
}

Json::object DefaultMetricsProvider::GetMemoryValuesFromFile(
    const std::string &path, const std::regex &pattern) {
    std::ifstream file_stream(path);
    Json::object metrics_map;
    if (!file_stream) {
        ALOGE("Could not open %s", path.c_str());
        return metrics_map;
    }

    std::string file((std::istreambuf_iterator<char>(file_stream)),
                     std::istreambuf_iterator<char>());
    std::smatch match;
    while (std::regex_search(file, match, pattern)) {
        metrics_map[match[1].str()] =
            Json((double)(strtoll(match[2].str().c_str(), nullptr, 10) *
                          BYTES_IN_KB));
        file = match.suffix().str();
    }
    return metrics_map;
}

int32_t DefaultMetricsProvider::GetOomScore() {
    std::stringstream ss_path;
    ss_path << "/proc/" << getpid() << "/oom_score";
    std::ifstream oom_file(ss_path.str());
    if (!oom_file) {
        ALOGE_ONCE("Could not open %s", ss_path.str().c_str());
        return -1;
    } else {
        int32_t oom_score;
        oom_file >> oom_score;
        return oom_score;
    }
}

}  // namespace memory_advice
