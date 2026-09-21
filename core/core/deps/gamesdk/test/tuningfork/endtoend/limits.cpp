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

#include "common.h"
#include "test_utils.h"
#include "tuningfork_test.h"

using namespace gamesdk_test;

namespace tuningfork_test {

TuningForkLogEvent TestEndToEndWithLimits() {
    const int NTICKS =
        101;  // note the first tick doesn't add anything to the histogram
    // {3} is the number of values in the Level enum in
    // tuningfork_extensions.proto
    auto settings = TestSettings(
        tf::Settings::AggregationStrategy::Submission::TICK_BASED, NTICKS - 1,
        2, {3}, {}, 2 /* (1 annotation * 2 instrument keys)*/);
    TuningForkTest test(settings, milliseconds(10));
    Annotation ann;
    std::unique_lock<std::mutex> lock(*test.rmutex_);
    for (int i = 0; i < NTICKS; ++i) {
        test.IncrementTime();
        ann.set_level(com::google::tuningfork::LEVEL_1);
        tf::SetCurrentAnnotation(tf::Serialize(ann));
        tf::FrameTick(TFTICK_PACED_FRAME_TIME);
        // These ticks should be ignored because we have set a limit on the
        // number of annotations.
        ann.set_level(com::google::tuningfork::LEVEL_2);
        tf::SetCurrentAnnotation(tf::Serialize(ann));
        auto err = tf::FrameTick(TFTICK_PACED_FRAME_TIME);
        // The last time around, sessions will have been swapped and this will
        // tick frame data
        EXPECT_TRUE(err == TUNINGFORK_ERROR_NO_MORE_SPACE_FOR_FRAME_TIME_DATA ||
                    i == NTICKS - 1);
    }
    // Wait for the upload thread to complete writing the string
    EXPECT_TRUE(test.cv_->wait_for(lock, s_test_wait_time) ==
                std::cv_status::no_timeout)
        << "Timeout";

    return test.Result();
}

extern TuningForkLogEvent ExpectedForAnnotationTest();

TEST(EndToEndTest, WithLimits) {
    auto result = TestEndToEndWithLimits();
    // This should be the same as the annotation test since we try to record 2
    // annotations but limit to 1.
    CheckStrings("AnnotationWithLimits", result, ExpectedForAnnotationTest());
}

}  // namespace tuningfork_test
