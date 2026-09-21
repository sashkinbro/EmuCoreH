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

#include <memory>
#include <string>

#include "Log.h"
#include "json11/json11.hpp"

using namespace json11;

namespace memory_advice {

namespace utils {

/*
 * A function that evaluates a given boolean expression and return the result.
 * The formula should contain a single greater than or less than operator. The
 * two sides of the operator can contain numbers, the four basic arithmetic
 * operators, and literals. The value for the literals are evaluated by checking
 * for them in the provided metrics.
 */
bool EvaluateBoolean(std::string formula, Json::object metrics);
/*
 * A function that evaluates a given arithmetic expression and returns the
 * result. The formula can contain numbers, the four basic arithmetic operators,
 * and literals. The value for the literals are evaluated by checking for them
 * in the provided metrics.
 */
double EvaluateNumber(std::string formula, Json::object metrics);
Json::object GetBuildInfo();

}  // namespace utils

}  // namespace memory_advice