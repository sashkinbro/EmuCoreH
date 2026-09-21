
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

#include "session.h"

namespace tuningfork {

FrameTimeMetricData* Session::CreateFrameTimeHistogram(
    MetricId id, const Settings::Histogram& settings) {
    frame_time_data_.push_back(
        std::make_unique<FrameTimeMetricData>(id, settings));
    auto p = frame_time_data_.back().get();
    available_frame_time_data_.push_back(p);
    return p;
}

LoadingTimeMetricData* Session::CreateLoadingTimeSeries(MetricId id) {
    loading_time_data_.push_back(std::make_unique<LoadingTimeMetricData>(id));
    auto p = loading_time_data_.back().get();
    available_loading_time_data_.push_back(p);
    return p;
}

MemoryMetricData* Session::CreateMemoryTimeSeries(MetricId id) {
    memory_data_.push_back(std::make_unique<MemoryMetricData>(id));
    auto p = memory_data_.back().get();
    available_memory_data_.push_back(p);
    return p;
}

BatteryMetricData* Session::CreateBatteryTimeSeries(MetricId id) {
    battery_data_.push_back(std::make_unique<BatteryMetricData>(id));
    auto p = battery_data_.back().get();
    available_battery_data_.push_back(p);
    return p;
}

ThermalMetricData* Session::CreateThermalTimeSeries(MetricId id) {
    thermal_data_.push_back(std::make_unique<ThermalMetricData>(id));
    auto p = thermal_data_.back().get();
    available_thermal_data_.push_back(p);
    return p;
}

void Session::RecordCrash(CrashReason reason) {
    std::lock_guard<std::mutex> lock(crash_mutex_);
    crash_data_.push_back(reason);
}

std::vector<CrashReason> Session::GetCrashReports() const {
    std::lock_guard<std::mutex> lock(crash_mutex_);
    return crash_data_;
}

void Session::ClearData() {
    std::lock_guard<std::mutex> lock(mutex_);
    metric_data_.clear();
    available_frame_time_data_.clear();
    available_loading_time_data_.clear();
    available_memory_data_.clear();
    available_battery_data_.clear();
    available_thermal_data_.clear();
    for (auto& p : frame_time_data_) {
        p->Clear();
        available_frame_time_data_.push_back(p.get());
    }
    for (auto& p : loading_time_data_) {
        p->Clear();
        available_loading_time_data_.push_back(p.get());
    }
    for (auto& p : memory_data_) {
        p->Clear();
        available_memory_data_.push_back(p.get());
    }
    for (auto& p : battery_data_) {
        p->Clear();
        available_battery_data_.push_back(p.get());
    }
    for (auto& p : thermal_data_) {
        p->Clear();
        available_thermal_data_.push_back(p.get());
    }
    time_.start = SystemTimePoint();
    time_.end = SystemTimePoint();
}

void Session::Ping(SystemTimePoint t) {
    if (time_.start == SystemTimePoint()) {
        time_.start = t;
    }
    time_.end = t;
}

}  // namespace tuningfork
