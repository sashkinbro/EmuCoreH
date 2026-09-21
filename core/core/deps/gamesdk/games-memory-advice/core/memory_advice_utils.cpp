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

#include "memory_advice_utils.h"

#include <stdlib.h>

#include <algorithm>
#include <cctype>
#include <fstream>
#include <iterator>
#include <map>
#include <sstream>
#include <streambuf>
#include <string>

#include "Log.h"
#include "jni/jni_wrap.h"
#include "memory_advice/memory_advice.h"
#include "system_utils.h"

#define LOG_TAG "MemoryAdvice:DeviceProfiler"

using namespace json11;

namespace memory_advice {

namespace utils {

bool EvaluateBoolean(std::string formula, Json::object metrics) {
    if (formula.find('>') != std::string::npos) {
        return EvaluateNumber(formula.substr(0, formula.find('>')), metrics) >
               EvaluateNumber(formula.substr(formula.find('>') + 1), metrics);
    } else if (formula.find('<') != std::string::npos) {
        return EvaluateNumber(formula.substr(0, formula.find('<')), metrics) <
               EvaluateNumber(formula.substr(formula.find('<') + 1), metrics);
    } else {
        return false;
    }
}

double EvaluateNumber(std::string formula, Json::object metrics) {
    if (formula.find('/') != std::string::npos) {
        return EvaluateNumber(formula.substr(0, formula.find('/')), metrics) /
               EvaluateNumber(formula.substr(formula.find('/') + 1), metrics);
    } else if (formula.find('*') != std::string::npos) {
        return EvaluateNumber(formula.substr(0, formula.find('*')), metrics) *
               EvaluateNumber(formula.substr(formula.find('*') + 1), metrics);
    } else if (formula.find('+') != std::string::npos) {
        return EvaluateNumber(formula.substr(0, formula.find('+')), metrics) +
               EvaluateNumber(formula.substr(formula.find('+') + 1), metrics);
    } else if (formula.find('-') != std::string::npos) {
        return EvaluateNumber(formula.substr(0, formula.find('-')), metrics) -
               EvaluateNumber(formula.substr(formula.find('-') + 1), metrics);
    } else if (std::isdigit(formula[0])) {
        return std::stod(formula);
    } else {
        return metrics[formula].number_value();
    }
}

Json::object GetBuildInfo() {
    // The current version of default.json only uses the sdk version from the
    // build parameters; so having this function only return that value saves
    // time during initialization of the library
    Json::object build_info;
    build_info["version"] = {
        {"sdk_int", Json(gamesdk::GetSystemPropAsInt("ro.build.version.sdk"))}};

    return build_info;
}

}  // namespace utils

}  // namespace memory_advice