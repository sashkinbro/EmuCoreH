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

#include <signal.h>

#include <functional>
#include <string>

namespace tuningfork {

class CrashHandler {
 public:
  CrashHandler();
  void Init(std::function<bool(void)> callback);
  virtual ~CrashHandler();

 private:
  std::function<bool(void)> callback_;
  bool handler_inited_ = false;
  std::string tf_crash_info_file_;
  static bool InstallHandlerLocked();
  static void RestoreHandlerLocked();
  static void SignalHandler(int sig, siginfo_t* info, void* ucontext);

  bool HandlerSignal(int sig, siginfo_t* info, void* ucontext);
};

}  // namespace tuningfork
