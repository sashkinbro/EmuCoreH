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

#include "memory_telemetry.h"

#include <stdlib.h>
#include <unistd.h>

#include <chrono>
#include <fstream>
#include <iterator>
#include <sstream>
#include <unordered_map>
#include <utility>
#include <vector>

#define LOG_TAG "TuningFork"
#include <iostream>
#include <string>

#include "Log.h"
#include "jni.h"
#include "session.h"

namespace tuningfork {

static const uint64_t HIST_START = 0;
static const uint64_t DEFAULT_HIST_END = 10000000000L;  // 10GB
static const uint32_t NUM_BUCKETS = 200;

constexpr size_t BYTES_IN_KB = 1024;

using memInfoMap = std::unordered_map<std::string, size_t>;

using namespace std::chrono;

Duration MemoryTelemetry::UploadPeriod() { return kMemoryMetricInterval; }

void MemoryReportingTask::DoWork(Session *session) {
    if (mem_info_provider_ != nullptr && mem_info_provider_->GetEnabled()) {
        auto d = session->GetData<MemoryMetricData>(metric_id_);
        d->Record(mem_info_provider_, time_provider_->TimeSinceProcessStart());
    }
}

void MemoryReportingTask::UpdateMetricId(MetricId id) { metric_id_ = id; }

uint64_t DefaultMemInfoProvider::GetNativeHeapAllocatedSize() {
    if (gamesdk::jni::IsValid()) {
        // Call android.os.Debug.getNativeHeapAllocatedSize()
        return android_debug_.getNativeHeapAllocatedSize();
    }
    return 0;
}

uint64_t DefaultMemInfoProvider::GetPss() {
    if (gamesdk::jni::IsValid()) {
        // Call android.os.Debug.getPss()
        return BYTES_IN_KB * android_debug_.getPss();
    }
    return 0;
}

uint64_t DefaultMemInfoProvider::GetAvailMem() {
    if (gamesdk::jni::IsValid()) {
        using namespace gamesdk::jni;
        java::Object obj = AppContext().getSystemService(
            android::content::Context::ACTIVITY_SERVICE);
        if (!obj.IsNull()) {
            android::app::ActivityManager activity_manager(std::move(obj));
            gamesdk::jni::android::app::MemoryInfo memory_info;
            activity_manager.getMemoryInfo(memory_info);
            return memory_info.availMem();
        }
    }
    return 0;
}

// Add entries to the given memInfoMap structure, or update the entries if the
// new value is higher.
static void getMemInfoFromFile(memInfoMap &data, const std::string &path) {
    std::ifstream file_stream(path);
    if (!file_stream) {
        ALOGE("Could not open %s", path.c_str());
        return;
    }
    for (std::string line; std::getline(file_stream, line);) {
        std::istringstream ss(line);
        std::vector<std::string> split(std::istream_iterator<std::string>{ss},
                                       std::istream_iterator<std::string>());
        if (split.size() == 3 && split[2] == "kB") {
            std::string &key = split[0];
            // Remove colon at end of first word
            key.pop_back();
            size_t value = atoi(split[1].c_str()) * BYTES_IN_KB;
            if (data.find(key) == data.end() || data[key] < value) {
                data[split[0]] = value;
            }
        }
    }
}

static std::pair<uint64_t, bool> getMemInfoValueFromData(
    const memInfoMap &data, const std::string &key) {
    if (data.count(key)) {
        return std::make_pair(data.at(key), true);
    } else {
        return std::make_pair(0, false);
    }
}

void DefaultMemInfoProvider::UpdateMemInfo() {
    std::stringstream ss_path;
    memInfoMap data;

    getMemInfoFromFile(data, "/proc/meminfo");
    ss_path << "/proc/" << memInfo.pid << "/status";
    getMemInfoFromFile(data, ss_path.str());

    // For the time being, only swap total is being used.
    // Disabling the rest for efficiency
    /*
    memInfo.active = getMemInfoValueFromData(data, "Active");
    memInfo.activeAnon = getMemInfoValueFromData(data, "Active(anon)");
    memInfo.activeFile = getMemInfoValueFromData(data, "Active(file)");
    memInfo.anonPages = getMemInfoValueFromData(data, "AnonPages");
    memInfo.commitLimit = getMemInfoValueFromData(data, "CommitLimit");
    memInfo.highTotal = getMemInfoValueFromData(data, "HighTotal");
    memInfo.lowTotal = getMemInfoValueFromData(data, "LowTotal");
    memInfo.memAvailable = getMemInfoValueFromData(data, "MemAvailable");
    memInfo.memFree = getMemInfoValueFromData(data, "MemFree");
    memInfo.memTotal = getMemInfoValueFromData(data, "MemTotal");
    */
    memInfo.swapTotal = getMemInfoValueFromData(data, "SwapTotal");
    /*
    memInfo.vmData = getMemInfoValueFromData(data, "VmData");
    memInfo.vmRss = getMemInfoValueFromData(data, "VmRSS");
    memInfo.vmSize = getMemInfoValueFromData(data, "VmSize");
    */
}

void DefaultMemInfoProvider::UpdateOomScore() {
    std::stringstream ss_path;
    ss_path << "/proc/" << memInfo.pid << "/oom_score";
    std::ifstream oom_file(ss_path.str());
    if (!oom_file) {
        ALOGE_ONCE("Could not open %s", ss_path.str().c_str());
    } else {
        oom_file >> memInfo.oom_score;
        if (!oom_file) {
            ALOGE_ONCE("Bad conversion in %s", ss_path.str().c_str());
        }
    }
}
DefaultMemInfoProvider::DefaultMemInfoProvider() {
    memInfo.initialized = true;
    memInfo.pid = (uint32_t)android_process_.myPid();
}

void DefaultMemInfoProvider::SetEnabled(bool enabled) {
    enabled_ = enabled;
}

bool DefaultMemInfoProvider::GetEnabled() const { return enabled_; }

void DefaultMemInfoProvider::SetDeviceMemoryBytes(uint64_t bytesize) {
    device_memory_bytes = bytesize;
}

uint64_t DefaultMemInfoProvider::GetDeviceMemoryBytes() const {
    return device_memory_bytes;
}

uint64_t DefaultMemInfoProvider::GetMemInfoOomScore() const {
    return memInfo.oom_score;
}

bool DefaultMemInfoProvider::IsMemInfoActiveAvailable() const {
    return memInfo.active.second;
}

bool DefaultMemInfoProvider::IsMemInfoActiveAnonAvailable() const {
    return memInfo.activeAnon.second;
}

bool DefaultMemInfoProvider::IsMemInfoActiveFileAvailable() const {
    return memInfo.activeFile.second;
}

bool DefaultMemInfoProvider::IsMemInfoAnonPagesAvailable() const {
    return memInfo.anonPages.second;
}

bool DefaultMemInfoProvider::IsMemInfoCommitLimitAvailable() const {
    return memInfo.commitLimit.second;
}

bool DefaultMemInfoProvider::IsMemInfoHighTotalAvailable() const {
    return memInfo.highTotal.second;
}

bool DefaultMemInfoProvider::IsMemInfoLowTotalAvailable() const {
    return memInfo.lowTotal.second;
}

bool DefaultMemInfoProvider::IsMemInfoMemAvailableAvailable() const {
    return memInfo.memAvailable.second;
}

bool DefaultMemInfoProvider::IsMemInfoMemFreeAvailable() const {
    return memInfo.memFree.second;
}

bool DefaultMemInfoProvider::IsMemInfoMemTotalAvailable() const {
    return memInfo.memTotal.second;
}

bool DefaultMemInfoProvider::IsMemInfoVmDataAvailable() const {
    return memInfo.vmData.second;
}

bool DefaultMemInfoProvider::IsMemInfoVmRssAvailable() const {
    return memInfo.vmRss.second;
}

bool DefaultMemInfoProvider::IsMemInfoVmSizeAvailable() const {
    return memInfo.vmSize.second;
}

uint64_t DefaultMemInfoProvider::GetMemInfoActiveBytes() const {
    return memInfo.active.first;
}

uint64_t DefaultMemInfoProvider::GetMemInfoActiveAnonBytes() const {
    return memInfo.activeAnon.first;
}

uint64_t DefaultMemInfoProvider::GetMemInfoActiveFileBytes() const {
    return memInfo.activeFile.first;
}

uint64_t DefaultMemInfoProvider::GetMemInfoAnonPagesBytes() const {
    return memInfo.anonPages.first;
}

uint64_t DefaultMemInfoProvider::GetMemInfoCommitLimitBytes() const {
    return memInfo.commitLimit.first;
}

uint64_t DefaultMemInfoProvider::GetMemInfoHighTotalBytes() const {
    return memInfo.highTotal.first;
}

uint64_t DefaultMemInfoProvider::GetMemInfoLowTotalBytes() const {
    return memInfo.lowTotal.first;
}

uint64_t DefaultMemInfoProvider::GetMemInfoMemAvailableBytes() const {
    return memInfo.memAvailable.first;
}

uint64_t DefaultMemInfoProvider::GetMemInfoMemFreeBytes() const {
    return memInfo.memFree.first;
}

uint64_t DefaultMemInfoProvider::GetMemInfoMemTotalBytes() const {
    return memInfo.memTotal.first;
}

uint64_t DefaultMemInfoProvider::GetMemInfoSwapTotalBytes() const {
    return memInfo.swapTotal.first;
}
uint64_t DefaultMemInfoProvider::GetMemInfoVmDataBytes() const {
    return memInfo.vmData.first;
}

uint64_t DefaultMemInfoProvider::GetMemInfoVmRssBytes() const {
    return memInfo.vmRss.first;
}

uint64_t DefaultMemInfoProvider::GetMemInfoVmSizeBytes() const {
    return memInfo.vmSize.first;
}

}  // namespace tuningfork
