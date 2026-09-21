/*
 * Copyright 2018 The Android Open Source Project
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

#include "backend.h"
#include "lifecycle_upload_event.h"
#include "runnable.h"
#include "session.h"

namespace tuningfork {

class UploadThread : public Runnable {
 private:
  const Session* ready_ = nullptr;
  bool upload_ = false;
  IBackend* backend_ = nullptr;
  TuningFork_UploadCallback upload_callback_ = nullptr;
  const TuningFork_Cache* persister_ = nullptr;
  IdProvider* id_provider_ = nullptr;
  // Optional isn't available until C++17 so use vector instead.
  std::vector<LifecycleUploadEvent> lifecycle_event_;
  const Session* lifecycle_event_session_ = nullptr;

 public:
  UploadThread(IdProvider* id_provider);
  ~UploadThread();

  UploadThread(const UploadThread&) = delete;
  UploadThread& operator=(const UploadThread&) = delete;

  void SetBackend(IBackend* backend);

  void InitialChecks(Session& session, IdProvider& id_provider,
                     const TuningFork_Cache* persister);

  void Start() override;
  Duration DoWork() override;

  // Returns true if we submitted, false if we are waiting for a previous
  // submit to complete If upload is false, the cache is serialized and saved,
  // not uploaded.
  bool Submit(const Session* session, bool upload);

  void SetUploadCallback(TuningFork_UploadCallback upload_callback) {
    upload_callback_ = upload_callback;
  }

  // Returns true if there were no errors.
  bool SendLifecycleEvent(const LifecycleUploadEvent& event,
                          const Session* session);
};

}  // namespace tuningfork
