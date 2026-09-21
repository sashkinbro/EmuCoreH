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

#include <jni.h>
#include <stdlib.h>
#include <unistd.h>

#include <chrono>
#include <fstream>
#include <iterator>
#include <sstream>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#define LOG_TAG "TFMetrics"
#include "Log.h"

// TODO(daitx): eliminate copied code and reference tuningfork instead

constexpr size_t BYTES_IN_KB = 1024;
constexpr size_t BYTES_IN_MB = BYTES_IN_KB * BYTES_IN_KB;
constexpr size_t LOOP_TIMES = 1000;

using memInfoMap = std::unordered_map<std::string, size_t>;

struct MemInfo {
    bool initialized = false;
    uint32_t pid;
    int oom_score;
    std::pair<uint64_t, bool> active;
    std::pair<uint64_t, bool> activeAnon;
    std::pair<uint64_t, bool> activeFile;
    std::pair<uint64_t, bool> anonPages;
    std::pair<uint64_t, bool> commitLimit;
    std::pair<uint64_t, bool> highTotal;
    std::pair<uint64_t, bool> lowTotal;
    std::pair<uint64_t, bool> memAvailable;
    std::pair<uint64_t, bool> memFree;
    std::pair<uint64_t, bool> memTotal;
    std::pair<uint64_t, bool> vmData;
    std::pair<uint64_t, bool> vmRss;
    std::pair<uint64_t, bool> vmSize;
};

// FIXME(daitx): FindClass("android/os/Debug") seems to crash the app on API
// level < 26 so we have to pass the class in instead, which might interfere in
// the result
uint64_t getNativeHeapAllocatedSize(JNIEnv *env, jclass android_os_debug) {
    jmethodID method = env->GetStaticMethodID(
        android_os_debug, "getNativeHeapAllocatedSize", "()J");
    if (method != NULL) {
        return (uint64_t)env->CallStaticLongMethod(android_os_debug, method);
    } else {
        return 0;
    }
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

void updateMemInfo(MemInfo &memInfo) {
    std::stringstream ss_path;
    memInfoMap data;

    getMemInfoFromFile(data, "/proc/meminfo");
    ss_path << "/proc/" << memInfo.pid << "/status";
    getMemInfoFromFile(data, ss_path.str());

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
    memInfo.vmData = getMemInfoValueFromData(data, "VmData");
    memInfo.vmRss = getMemInfoValueFromData(data, "VmRSS");
    memInfo.vmSize = getMemInfoValueFromData(data, "VmSize");
}

void updateOomScore(MemInfo &memInfo) {
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

void printMetric(std::stringstream &ss, std::string name,
                 std::pair<uint64_t, bool> val) {
    ss << name << ": ";
    if (val.second) {
        ss << val.first / BYTES_IN_MB << " MB";
    } else {
        ss << '-';
    }
    ss << std::endl;
}

void printToStringstream(MemInfo &memInfo, std::stringstream &ss) {
    ss << "PID: " << memInfo.pid << std::endl;
    printMetric(ss, "active", memInfo.active);
    printMetric(ss, "activeAnon", memInfo.activeAnon);
    printMetric(ss, "activeFile", memInfo.activeFile);
    printMetric(ss, "anonPages", memInfo.anonPages);
    printMetric(ss, "commitLimit", memInfo.commitLimit);
    printMetric(ss, "highTotal", memInfo.highTotal);
    printMetric(ss, "lowTotal", memInfo.lowTotal);
    printMetric(ss, "memAvailable", memInfo.memAvailable);
    printMetric(ss, "memFree", memInfo.memFree);
    printMetric(ss, "memTotal", memInfo.memTotal);
    printMetric(ss, "vmData", memInfo.vmData);
    printMetric(ss, "vmRss", memInfo.vmRss);
    printMetric(ss, "vmSize", memInfo.vmSize);
}

extern "C" JNIEXPORT jstring JNICALL
Java_test_tuningfork_metricstest_MainActivity_measureMetricsPerformance(
    JNIEnv *env, jobject thiz, jclass android_os_debug) {
    std::stringstream ss;
    MemInfo memInfo;
    memset(&memInfo, 0, sizeof(MemInfo));
    uint64_t heap = 0;

    ALOGI("Starting metrics collection");
    auto startTime = std::chrono::steady_clock::now();
    for (size_t i = 0; i < LOOP_TIMES; i++) {
        heap = getNativeHeapAllocatedSize(env, android_os_debug);
        memInfo.pid = (uint32_t)getpid();
        updateMemInfo(memInfo);
        updateOomScore(memInfo);
    }
    auto endTime = std::chrono::steady_clock::now();
    uint32_t avg_microseconds =
        std::chrono::duration_cast<std::chrono::microseconds>(
            (endTime - startTime) / LOOP_TIMES)
            .count();
    ALOGI("Ending metrics collection. Average time for %zu iterations: %u us",
          LOOP_TIMES, avg_microseconds);

    ss << "Average time: " << avg_microseconds << " us (" << LOOP_TIMES
       << " iterations)" << std::endl
       << std::endl;
    ss << "Values for last iteration:" << std::endl;
    ss << "getNativeHeapAllocatedSize: " << heap / BYTES_IN_KB << " KB"
       << std::endl;
    printToStringstream(memInfo, ss);
    return env->NewStringUTF(ss.str().c_str());
}
