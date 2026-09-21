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

#include <algorithm>
#include <set>
#include <sstream>
#include <string>

#include "gtest/gtest.h"
#include "jni/jni_helper.h"

using ::testing::EmptyTestEventListener;
using ::testing::InitGoogleTest;
using ::testing::Test;
using ::testing::TestCase;
using ::testing::TestEventListeners;
using ::testing::TestInfo;
using ::testing::TestPartResult;
using ::testing::UnitTest;

namespace {

static JNIEnv* s_env = 0;
static jobject s_context = 0;

// Record the output of googletest tests
class GTestRecorder : public EmptyTestEventListener {
    std::vector<std::string> tests_started;
    std::set<std::string> tests_completed;
    std::vector<std::string> success_invocations;  // Only from SUCCESS macros
    std::vector<std::string>
        failed_invocations;  // From any failed EXPECT or ASSERT
    bool overall_success;

   private:
    // Called before any test activity starts.
    void OnTestProgramStart(const UnitTest& /* unit_test */) override {
        overall_success = false;
    }

    // Called after all test activities have ended.
    void OnTestProgramEnd(const UnitTest& unit_test) override {
        overall_success = unit_test.Passed();
    }

    // Called before a test starts.
    void OnTestStart(const TestInfo& test_info) override {
        tests_started.push_back(std::string(test_info.test_case_name()) + "." +
                                test_info.name());
    }

    // Called after a failed assertion or a SUCCEED() invocation.
    void OnTestPartResult(const TestPartResult& test_part_result) override {
        std::stringstream record;
        // This assumes we are not running tests in parallel, so the last one
        // added to tests_started is the test for which we are getting the
        // partial result.
        record << tests_started.back() << '\n';
        record << test_part_result.file_name() << ":"
               << test_part_result.line_number() << '\n'
               << test_part_result.summary() << '\n';
        if (test_part_result.failed()) {
            failed_invocations.push_back(record.str());
        } else {
            success_invocations.push_back(record.str());
        }
    }

    // Called after a test ends.
    void OnTestEnd(const TestInfo& test_info) override {
        tests_completed.insert(std::string(test_info.test_case_name()) + "." +
                               test_info.name());
    }

   public:
    std::string GetResult() const {
        std::stringstream result;
        result << "TESTS " << (overall_success ? "SUCCEEDED" : "FAILED")
               << '\n';
        result << "\nTests that ran to completion:\n";
        for (auto s : tests_completed) {
            result << s << '\n';
        }
        std::set<std::string> not_completed;
        std::set<std::string> tests_started_set(tests_started.begin(),
                                                tests_started.end());
        std::set_difference(tests_started_set.begin(), tests_started_set.end(),
                            tests_completed.begin(), tests_completed.end(),
                            std::inserter(not_completed, not_completed.end()));
        if (not_completed.size() > 0) {
            result << "\nTests that started but failed to complete:\n";
            for (auto s : not_completed) {
                result << s << '\n';
            }
        }
        if (!failed_invocations.empty()) {
            result << "\nFailures:\n";
            for (auto s : failed_invocations) result << s << '\n';
        }
        if (!success_invocations.empty()) {
            result << "\nExplicitly recorded successes:\n";
            for (auto s : success_invocations) result << s << '\n';
        }
        return result.str();
    }
};  // class GTestRecorder

}  // namespace

extern "C" bool init_jni_for_tests() {
    gamesdk::jni::Init(s_env, s_context);
    return true;
}
extern "C" void clear_jni_for_tests() { gamesdk::jni::Destroy(); }

extern "C" int shared_main(int argc, char* argv[], JNIEnv* env, jobject context,
                           std::string& messages) {
    ::testing::InitGoogleTest(&argc, argv);

    // Set up Java env
    s_env = env;
    s_context = context;
    init_jni_for_tests();

    // Set up result recorder
    UnitTest& unit_test = *UnitTest::GetInstance();
    TestEventListeners& listeners = unit_test.listeners();
    delete listeners.Release(listeners.default_result_printer());
    auto recorder = std::make_shared<GTestRecorder>();
    listeners.Append(recorder.get());

    // Run tests
    int result = RUN_ALL_TESTS();
    messages = recorder->GetResult();
    return result;
}
