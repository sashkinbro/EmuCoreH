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

#include <string>

#include "core/common.h"
#include "core/request_info.h"
#include "tuningfork/tuningfork.h"

namespace tuningfork {

// Request to an HTTP endpoint.
class HttpRequest {
  std::string base_url_;
  std::string api_key_;
  Duration timeout_;
  bool allow_metered_ = false;

 public:
  HttpRequest(std::string base_url, std::string api_key, Duration timeout)
      : base_url_(base_url), api_key_(api_key), timeout_(timeout) {}
  virtual ~HttpRequest() {}
  std::string GetURL(std::string rpcname) const;
  virtual TuningFork_ErrorCode Send(const std::string& rpc_name,
                                    const std::string& request_json,
                                    int& response_code,
                                    std::string& response_body);
  HttpRequest& AllowMetered(bool allow) {
    allow_metered_ = allow;
    return *this;
  }
};

}  // namespace tuningfork
