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

#include "test_utils.h"

#include <regex>
#include <sstream>
#include <list>
#include <mutex>
#include <random>

#include "gtest/gtest.h"

namespace memory_advice_test {

static std::list<std::unique_ptr<char[]>> allocated_bytes_list;
static std::mutex allocated_mutex;

std::string GetAdviceString(const std::string& avail_mem,
                            const std::string& predicted_avail,
                            const std::string& oom_score, bool with_warnings) {
    if (with_warnings) {
        return R"MA(
    {
      "metrics":{
        "MemoryInfo":{
          "_meta":["duration", !REGEX(\d)],
           "availMem":)MA" +
               avail_mem + R"MA(
        },
        "meta": {
          "time": !REGEX(\d+)
        },
        "predictedAvailable": )MA" +
               predicted_avail + R"MA(,
        "proc": {
          "_meta":["duration", !REGEX(\d)],
          "oom_score": )MA" +
               oom_score + R"MA(
        }
      },
      "warnings": [
        {
          "formula": "predictedAvailable<0.15",
          "level": "red"
        },
        {
          "formula": "predictedAvailable<0.20",
          "level": "yellow"
        }
      ]
    }
    )MA";
    } else {
        return R"MA(
    {
      "metrics":{
        "MemoryInfo":{
          "_meta":["duration", !REGEX(\d)],
           "availMem":)MA" +
               avail_mem + R"MA(
        },
        "meta": {
          "time": !REGEX(\d+)
        },
        "predictedAvailable": )MA" +
               predicted_avail + R"MA(,
        "proc": {
          "_meta":["duration", !REGEX(\d)],
          "oom_score": )MA" +
               oom_score + R"MA(
        }
      }
    }
    )MA";
    }
}

void FillRandom(char* bytes, size_t size) {
  std::minstd_rand rng;
  int32_t* ptr = reinterpret_cast<int32_t*>(bytes);
  std::generate(ptr, ptr + size/4, [&]() -> int32_t { return rng(); });
  // Don't worry about filling the last few bytes if size isn't a multiple of 4.
}

void AllocateMemory(uint64_t nbytes) {
  std::lock_guard<std::mutex> guard(allocated_mutex);
  try {
    auto allocated_bytes = new char[nbytes];
    // Note that without setting the memory, the available memory figure doesn't go down.
    FillRandom(allocated_bytes, nbytes);
    allocated_bytes_list.push_back(std::unique_ptr<char[]>{allocated_bytes});
  } catch(std::bad_alloc& ex) {
  }
}

void DeallocateAllMemory() {
  std::lock_guard<std::mutex> guard(allocated_mutex);
  while (allocated_bytes_list.size() > 0) {
    allocated_bytes_list.pop_back();
  }
}

} // namespace memory_advice_test
