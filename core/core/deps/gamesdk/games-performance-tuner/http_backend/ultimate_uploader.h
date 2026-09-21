
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

#include "core/runnable.h"
#include "core/tuningfork_utils.h"
#include "http_request.h"
#include "proto/protobuf_util.h"

namespace tuningfork {

// This class periodically listens on a separate thread for upload packets from
// the persister and performs the HTTP request to do the upload.
class UltimateUploader : public Runnable {
  const TuningFork_Cache* persister_ = nullptr;
  HttpRequest request_;

 public:
  UltimateUploader(const TuningFork_Cache* persister,
                   const HttpRequest& request);
  virtual Duration DoWork() override;
  virtual void Run() override;

 private:
  bool CheckUploadPending();
};

}  // namespace tuningfork
