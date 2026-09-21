/*
 * Copyright 2022 The Android Open Source Project
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

#include <memory_advice/memory_advice.h>
#include <memory_advice/memory_advice_debug.h>

#include <core/memory_advice_impl.h>
#include <core/memory_advice_internal.h>
#include <core/state_watcher.h>

#include <regex>
#include <sstream>
#include <string>


#define LOG_TAG "MemoryAdvice"
#include "Log.h"
#include "json11/json11.hpp"

#include "gtest/gtest.h"
#include "../memory_utils.h"
#include "test_utils.h"

namespace memory_advice_test {

extern const char* parameters_string;

std::string TestEndToEnd() {
  memory_advice::MemoryAdviceImpl* s_impl;
  s_impl = new memory_advice::MemoryAdviceImpl(parameters_string, nullptr, nullptr, nullptr);

  return json11::Json(s_impl->GetAdvice()).dump();
}

TEST(EndToEndTest, Base) {
  auto result = TestEndToEnd();
  std::string expected = GetAdviceString("!REGEX(\\d+)", "0.0!REGEX(\\d+)", "!REGEX(\\d{1,3})");
  gamesdk_test::CheckStrings("Base", result, expected);
}

} // memory_advice_test
