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

#include "Trace.h"

#include <sys/system_properties.h>

#include "common.h"
#include "test_utils.h"
#include "tuningfork_test.h"

using namespace gamesdk_test;

namespace tuningfork_test {

/*test the Trace.h api*/
TEST(EndToEndTest, systrace) {
    char osVersionStr[PROP_VALUE_MAX + 1];
    __system_property_get("ro.build.version.release", osVersionStr);
    int osVersion = atoi(osVersionStr);
    std::unique_ptr<gamesdk::Trace> trace_ = gamesdk::Trace::create();

    if (osVersion >= 6 /*api level 23*/) {
        ASSERT_TRUE(trace_->supportsBasicATrace());
    } else {
        ASSERT_FALSE(trace_->supportsBasicATrace());
    }

    if (osVersion >= 10 /*api level 29*/) {
        ASSERT_TRUE(trace_->supportsFullATrace());
    } else {
        ASSERT_FALSE(trace_->supportsFullATrace());
    }

    // Check that calls don't fail even if full ATrace isn't supported
    trace_->beginSection("test");
    trace_->endSection();
    trace_->beginAsyncSection("test", 101);
    trace_->endAsyncSection("test", 101);
}

}  // namespace tuningfork_test
