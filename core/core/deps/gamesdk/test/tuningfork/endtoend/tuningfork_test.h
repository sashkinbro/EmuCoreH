/*
 * Copyright 2021 The Android Open Source Project
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

#include "common.h"
#include "test_backend.h"
#include "test_battery_provider.h"
#include "test_download_backend.h"
#include "test_meminfo_provider.h"
#include "test_time_provider.h"

namespace tuningfork_test {

class TuningForkTest {
 public:
  std::shared_ptr<std::condition_variable> cv_ =
      std::make_shared<std::condition_variable>();
  std::shared_ptr<std::mutex> rmutex_ = std::make_shared<std::mutex>();
  TestBackend test_backend_;
  TestTimeProvider time_provider_;
  TestMemInfoProvider meminfo_provider_;
  TestBatteryProvider battery_provider_;
  TuningFork_ErrorCode init_return_value_;

  TuningForkTest(const tf::Settings& settings,
                 tf::Duration tick_size = milliseconds(20),
                 const std::shared_ptr<TestDownloadBackend>& download_backend =
                     std::make_shared<TestDownloadBackend>(),
                 bool enable_meminfo = false,
                 bool enable_battery_reporting = false)
      : test_backend_(cv_, rmutex_, download_backend),
        time_provider_(tick_size),
        meminfo_provider_(enable_meminfo),
        battery_provider_(enable_battery_reporting) {
    tf::RequestInfo info = {};
    info.tuningfork_version = ANDROID_GAMESDK_PACKED_VERSION(1, 0, 0);
    init_return_value_ =
        tf::Init(settings, &info, &test_backend_, &time_provider_,
                 &meminfo_provider_, &battery_provider_);
    EXPECT_EQ(init_return_value_, TUNINGFORK_ERROR_OK) << "Bad Init";
  }

  ~TuningForkTest() {
    if (init_return_value_ == TUNINGFORK_ERROR_OK) {
      tuningfork::Destroy();
      tuningfork::KillDownloadThreads();
    }
  }

  TuningForkLogEvent Result() const { return test_backend_.result; }

  void ClearResult() { test_backend_.Clear(); }

  void WaitForMemoryUpdates(size_t expected_num_requests) {
    const int maxWaits = 10;
    int waits = 0;
    while (waits++ < maxWaits &&
           meminfo_provider_.numNativeHeapRequests < expected_num_requests) {
      std::this_thread::sleep_for(milliseconds(10));
    }
  }
  void IncrementTime(int count = 1) {
    for (int i = 0; i < count; ++i) time_provider_.Increment();
  }
};

}  // namespace tuningfork_test
