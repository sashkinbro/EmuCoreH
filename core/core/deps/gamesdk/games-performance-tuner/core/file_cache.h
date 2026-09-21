/*
 * Copyright 2019 The Android Open Source Project
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
#include <string>

#include "tuningfork/tuningfork.h"
#include "tuningfork_internal.h"

// Implementation of a TuningFork_Cache that persists to local storage
namespace tuningfork {

class FileCache {
  std::string path_;
  TuningFork_Cache c_cache_;
  std::mutex mutex_;

 public:
  explicit FileCache(const std::string& path = "");

  FileCache(const FileCache&) = delete;
  FileCache& operator=(const FileCache&) = delete;

  void SetDir(const std::string& path = "") { path_ = path; }

  const TuningFork_Cache* GetCCache() const { return &c_cache_; }

  TuningFork_ErrorCode Get(uint64_t key,
                           TuningFork_CProtobufSerialization* value);
  TuningFork_ErrorCode Set(uint64_t key,
                           const TuningFork_CProtobufSerialization* value);
  TuningFork_ErrorCode Remove(uint64_t key);

  TuningFork_ErrorCode Clear();

  // Returns false if path is non-writeable
  bool IsValid() const;
};

}  // namespace tuningfork
