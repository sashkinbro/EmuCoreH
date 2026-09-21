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

#pragma once

#include <list>
#include <mutex>
#include <string>

namespace memory_advice_test {

const std::string kArrayWildcard = "[**]";
const std::string kRegexPattern = "!REGEX";
const uint64_t kBytesInMegabyte = 1000000;

// Compare two strings, ignoring any whitespace. Also, the following patterns in
// s1 can be used:
// '[**]' is an array wildcard - it matches nested arrays.
// '!REGEX(.*) will match the regex in brackets.
bool CompareIgnoringWhitespace(std::string s0, std::string s1,
                               std::string* error_msg = nullptr);

// Compare the strings ignoring whitespace and EXPECT_TRUE that they're the
// same.
bool CheckStrings(const std::string& name, const std::string& result,
                  const std::string& expected);

// Generates an advice string that satisfies the given parameters
std::string GetAdviceString(const std::string& avail_mem,
                            const std::string& predicted_avail,
                            const std::string& oom_score,
                            bool with_warnings = false);

// Allocates a given number of bytes
void AllocateMemory(uint64_t nbytes);

// Deallocates all memory that has currently been allocated
void DeallocateAllMemory();
}  // namespace memory_advice_test
