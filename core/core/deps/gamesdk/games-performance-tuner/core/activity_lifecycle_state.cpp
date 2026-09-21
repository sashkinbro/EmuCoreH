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

#include "activity_lifecycle_state.h"

#include <android/api-level.h>
#include <stdlib.h>

#include <cstdio>
#include <fstream>
#include <sstream>

#include "Log.h"
#include "jni/jni_wrap.h"
#include "system_utils.h"
#include "tuningfork_utils.h"

namespace tuningfork {

ActivityLifecycleState::ActivityLifecycleState() {
    std::stringstream lifecycle_path_builder;
    lifecycle_path_builder << DefaultTuningForkSaveDirectory();
    file_utils::CheckAndCreateDir(lifecycle_path_builder.str());
    lifecycle_path_builder << "/lifecycle.bin";
    tf_lifecycle_path_str_ = lifecycle_path_builder.str();
    ALOGV("Path to lifecycle file: %s", tf_lifecycle_path_str_.c_str());

    std::stringstream crash_info_path_builder;
    crash_info_path_builder << DefaultTuningForkSaveDirectory()
                            << "/crash_info.bin";
    tf_crash_info_file_ = crash_info_path_builder.str();
    ALOGV("Path to crash info file: %s", tf_crash_info_file_.c_str());
}

ActivityLifecycleState::~ActivityLifecycleState() {}

bool ActivityLifecycleState::SetNewState(TuningFork_LifecycleState new_state) {
    current_state_ = new_state;
    if (current_state_ == TUNINGFORK_STATE_ONSTART)
        app_on_foreground_ = true;
    else if (current_state_ == TUNINGFORK_STATE_ONSTOP)
        app_on_foreground_ = false;
    TuningFork_LifecycleState saved_state = GetStoredState();
    StoreStateToDisk(current_state_);
    ALOGV(
        "New lifecycle state recorded: %s, app on foreground: %d, saved state: "
        "%s",
        GetStateName(current_state_), app_on_foreground_,
        GetStateName(saved_state));
    return !(current_state_ == TUNINGFORK_STATE_ONCREATE &&
             saved_state == TUNINGFORK_STATE_ONSTART);
}

const char* ActivityLifecycleState::GetStateName(
    TuningFork_LifecycleState state) {
    switch (state) {
        case TUNINGFORK_STATE_UNINITIALIZED:
            return "uninitialized";
        case TUNINGFORK_STATE_ONCREATE:
            return "onCreate";
        case TUNINGFORK_STATE_ONSTART:
            return "onStart";
        case TUNINGFORK_STATE_ONSTOP:
            return "onStop";
        case TUNINGFORK_STATE_ONDESTROY:
            return "onDestroy";
    }
}

TuningFork_LifecycleState ActivityLifecycleState::GetStateFromString(
    const std::string& name) {
    if (name == "onCreate") {
        return TUNINGFORK_STATE_ONCREATE;
    } else if (name == "onStart") {
        return TUNINGFORK_STATE_ONSTART;
    } else if (name == "onStop") {
        return TUNINGFORK_STATE_ONSTOP;
    } else if (name == "onDestroy") {
        return TUNINGFORK_STATE_ONDESTROY;
    } else {
        return TUNINGFORK_STATE_UNINITIALIZED;
    }
}

TuningFork_LifecycleState ActivityLifecycleState::GetStoredState() {
    if (!file_utils::FileExists(tf_lifecycle_path_str_)) {
        return TUNINGFORK_STATE_UNINITIALIZED;
    }
    std::ifstream file(tf_lifecycle_path_str_);
    std::string state_name;
    file >> state_name;
    return GetStateFromString(state_name);
}

void ActivityLifecycleState::StoreStateToDisk(TuningFork_LifecycleState state) {
    std::ofstream file(tf_lifecycle_path_str_);
    if (file.is_open()) {
        file << GetStateName(state);
    } else {
        ALOGE_ONCE("Lifecycle state couldn't be stored.");
    }
}

TuningFork_LifecycleState ActivityLifecycleState::GetCurrentState() {
    return current_state_;
}

bool ActivityLifecycleState::IsAppOnForeground() { return app_on_foreground_; }

CrashReason ActivityLifecycleState::GetLatestCrashReason() {
    if (!file_utils::FileExists(tf_crash_info_file_)) {
        return GetReasonFromActivityManager();
    } else {
        std::ifstream file(tf_crash_info_file_);
        int signal;
        file >> signal;
        file.close();
        if (remove(tf_crash_info_file_.c_str())) {
            ALOGE_ONCE("Failed to delete the crash info file.");
        }
        return ConvertSignalToCrashReason(signal);
    }
}

CrashReason ActivityLifecycleState::ConvertSignalToCrashReason(int signal) {
    switch (signal) {
        case SIGFPE:
            return FLOATING_POINT_ERROR;
        case SIGSEGV:
            return SEGMENTATION_FAULT;
        case SIGBUS:
            return BUS_ERROR;
        default:
            return CRASH_REASON_UNSPECIFIED;
    }
}

CrashReason ActivityLifecycleState::GetReasonFromActivityManager() {
    if (gamesdk::GetSystemPropAsInt("ro.build.version.sdk") >= 30) {
        using namespace gamesdk::jni;
        auto app_context = AppContext();
        auto pm = app_context.getPackageManager();
        CHECK_FOR_JNI_EXCEPTION_AND_RETURN(CRASH_REASON_UNSPECIFIED);
        std::string package_name = app_context.getPackageName().C();
        CHECK_FOR_JNI_EXCEPTION_AND_RETURN(CRASH_REASON_UNSPECIFIED);

        java::Object obj = app_context.getSystemService(
            android::content::Context::ACTIVITY_SERVICE);
        CHECK_FOR_JNI_EXCEPTION_AND_RETURN(CRASH_REASON_UNSPECIFIED);
        if (!obj.IsNull()) {
            android::app::ActivityManager activity_manager(std::move(obj));
            java::util::List reasons =
                activity_manager.getHistoricalProcessExitReasons(package_name,
                                                                 0, 0);
            CHECK_FOR_JNI_EXCEPTION_AND_RETURN(CRASH_REASON_UNSPECIFIED);
            if (!reasons.isEmpty()) {
                android::app::ApplicationExitInfo exit_info(
                    std::move(reasons.get(0)));
                int reason = exit_info.getReason();
                if (reason ==
                    android::app::ApplicationExitInfo::REASON_LOW_MEMORY) {
                    return LOW_MEMORY;
                }
            }
        }
    }
    return CRASH_REASON_UNSPECIFIED;
}
}  // namespace tuningfork
