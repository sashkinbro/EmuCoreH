/*
 * Copyright 2021 The Android Open Source Project
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

#include <mutex>
#include <utility>
#include <vector>

#include "core/memory_telemetry.h"
#include "core/tuningfork_internal.h"
#include "gtest/gtest.h"
#include "http_backend/http_backend.h"
#include "lite/dev_tuningfork.pb.h"
#include "lite/tuningfork.pb.h"
#include "proto/protobuf_util.h"

#ifndef LOG_TAG
#define LOG_TAG "TFTest"
#endif

#include "Log.h"

namespace tuningfork_test {

using ::com::google::tuningfork::Annotation;
using ::com::google::tuningfork::FidelityParams;
typedef std::string TuningForkLogEvent;
namespace tf = tuningfork;
using namespace std::chrono;

constexpr tf::Duration s_test_wait_time = seconds(1);

// Strings with expected default session contexts.
extern const std::string session_context;
extern const std::string session_context_loading;

// Generate test Settings using the input parameters.
tf::Settings TestSettings(
    tf::Settings::AggregationStrategy::Submission method, int n_ticks,
    int n_keys, std::vector<uint32_t> annotation_size,
    const std::vector<tf::Settings::Histogram>& hists = {},
    int num_frame_time_histograms = 0, int num_loading_time_histograms = 0);

// Return a string with all returns replaced with single spaces.
std::string ReplaceReturns(std::string in);

}  // namespace tuningfork_test
