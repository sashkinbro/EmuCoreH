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

#include "tuningfork/tuningfork_extra.h"

#include <dlfcn.h>

#include <chrono>
#include <cinttypes>
#include <cstdlib>
#include <fstream>
#include <memory>
#include <mutex>
#include <sstream>
#include <thread>
#include <vector>

#include "jni/jni_helper.h"
#include "proto/protobuf_util.h"
#include "tuningfork_internal.h"
#include "tuningfork_utils.h"

#define LOG_TAG "TuningFork"
#include <android/asset_manager_jni.h>
#include <jni.h>

#include "Log.h"

namespace tuningfork {

// Get the name of the tuning fork save file. Returns true if the directory
//  for the file exists and false on error.
bool GetSavedFileName(std::string& name) {
    // Create tuningfork/version folder if it doesn't exist
    std::stringstream tf_path_str;
    tf_path_str << DefaultTuningForkSaveDirectory();
    if (!file_utils::CheckAndCreateDir(tf_path_str.str())) {
        return false;
    }
    tf_path_str << "/V" << apk_utils::GetVersionCode();
    if (!file_utils::CheckAndCreateDir(tf_path_str.str())) {
        return false;
    }
    tf_path_str << "/saved_fp.bin";
    name = tf_path_str.str();
    return true;
}

// Get a previously save fidelity param serialization.
bool GetSavedFidelityParams(ProtobufSerialization& params) {
    std::string save_filename;
    if (GetSavedFileName(save_filename)) {
        TuningFork_CProtobufSerialization c_params;
        if (file_utils::LoadBytesFromFile(save_filename, &c_params)) {
            ALOGI("Loaded fps from %s (%u bytes)", save_filename.c_str(),
                  c_params.size);
            params = ToProtobufSerialization(c_params);
            TuningFork_CProtobufSerialization_free(&c_params);
            return true;
        }
        ALOGI("Couldn't load fps from %s", save_filename.c_str());
    }
    return false;
}

// Save fidelity params to the save file.
bool SaveFidelityParams(const ProtobufSerialization& params) {
    std::string save_filename;
    if (GetSavedFileName(save_filename)) {
        std::ofstream save_file(save_filename, std::ios::binary);
        if (save_file.good()) {
            save_file.write(reinterpret_cast<const char*>(params.data()),
                            params.size());
            ALOGI("Saved fps to %s (%zu bytes)", save_filename.c_str(),
                  params.size());
            return true;
        }
        ALOGI("Couldn't save fps to %s", save_filename.c_str());
    }
    return false;
}

// Check if we have saved fidelity params.
bool SavedFidelityParamsFileExists() {
    std::string save_filename;
    if (GetSavedFileName(save_filename)) {
        return file_utils::FileExists(save_filename);
    }
    return false;
}

static bool s_kill_thread = false;
static std::unique_ptr<std::thread> s_fp_thread;

// Download FPs on a separate thread
TuningFork_ErrorCode StartFidelityParamDownloadThread(
    const ProtobufSerialization& default_params,
    TuningFork_FidelityParamsCallback fidelity_params_callback,
    int initialTimeoutMs, int ultimateTimeoutMs) {
    if (fidelity_params_callback == nullptr)
        return TUNINGFORK_ERROR_BAD_PARAMETER;
    static std::mutex threadMutex;
    std::lock_guard<std::mutex> lock(threadMutex);
    if (s_fp_thread.get() && s_fp_thread->joinable()) {
        ALOGW("Fidelity param download thread already started");
        return TUNINGFORK_ERROR_DOWNLOAD_THREAD_ALREADY_STARTED;
    }
    s_kill_thread = false;
    s_fp_thread = std::make_unique<std::thread>([=]() {
        ProtobufSerialization params;
        auto waitTime = std::chrono::milliseconds(initialTimeoutMs);
        bool first_time = true;
        auto upload_defaults_first_time = [&]() {
            if (first_time) {
                TuningFork_CProtobufSerialization cpbs;
                ToCProtobufSerialization(default_params, cpbs);
                if (fidelity_params_callback) fidelity_params_callback(&cpbs);
                TuningFork_CProtobufSerialization_free(&cpbs);
                first_time = false;
            }
        };
        while (!s_kill_thread) {
            auto startTime = std::chrono::steady_clock::now();
            auto err =
                GetFidelityParameters(default_params, params, waitTime.count());
            if (err == TUNINGFORK_ERROR_OK ||
                err == TUNINGFORK_ERROR_NO_FIDELITY_PARAMS) {
                if (err == TUNINGFORK_ERROR_NO_FIDELITY_PARAMS) {
                    ALOGI("Got empty fidelity params from server");
                    upload_defaults_first_time();
                } else {
                    ALOGI("Got fidelity params from server");
                    if (gamesdk::jni::IsValid()) SaveFidelityParams(params);
                    TuningFork_CProtobufSerialization cpbs;
                    ToCProtobufSerialization(params, cpbs);
                    if (fidelity_params_callback) {
                        fidelity_params_callback(&cpbs);
                    }
                    TuningFork_CProtobufSerialization_free(&cpbs);
                }
                break;
            } else {
                ALOGI("Could not get fidelity params from server : err = %d",
                      err);
                upload_defaults_first_time();
                // Wait if the call returned earlier than expected
                auto dt = std::chrono::steady_clock::now() - startTime;
                if (waitTime > dt) std::this_thread::sleep_for(waitTime - dt);
                if (waitTime.count() > ultimateTimeoutMs) {
                    ALOGW("Not waiting any longer for fidelity params");
                    break;
                }
                waitTime *= 2;  // back off
            }
        }
        if (gamesdk::jni::IsValid()) gamesdk::jni::DetachThread();
    });
    return TUNINGFORK_ERROR_OK;
}

// Load fidelity params from assets/tuningfork/<filename>
// Ownership of serializations is passed to the caller: call
//  TuningFork_CProtobufSerialization_free to deallocate any memory.
TuningFork_ErrorCode FindFidelityParamsInApk(const std::string& filename,
                                             ProtobufSerialization& fp) {
    std::stringstream full_filename;
    full_filename << "tuningfork/" << filename;
    if (!apk_utils::GetAssetAsSerialization(full_filename.str().c_str(), fp)) {
        ALOGE("Can't find %s", full_filename.str().c_str());
        return TUNINGFORK_ERROR_NO_FIDELITY_PARAMS;
    }
    ALOGV("Found %s", full_filename.str().c_str());
    return TUNINGFORK_ERROR_OK;
}

std::unique_ptr<ProtobufSerialization> GetTrainingParams(
    const Settings& settings) {
    std::unique_ptr<ProtobufSerialization> training_params;
    auto cpbs = settings.c_settings.training_fidelity_params;
    if (cpbs != nullptr) {
        training_params = std::make_unique<ProtobufSerialization>(
            ToProtobufSerialization(*cpbs));
    }
    return training_params;
}

TuningFork_ErrorCode GetDefaultsFromAPKAndDownloadFPs(
    const Settings& settings) {
    ProtobufSerialization default_params;
    // Use the saved params as default, if they exist
    if (SavedFidelityParamsFileExists()) {
        ALOGI("Using saved default params");
        GetSavedFidelityParams(default_params);
    } else {
        // Use the training mode params if they are present
        auto training_params = GetTrainingParams(settings);
        if (training_params.get() != nullptr) {
            default_params = *training_params;
        } else {
            // Try to get the parameters from file.
            if (settings.default_fidelity_parameters_filename.empty())
                return TUNINGFORK_ERROR_INVALID_DEFAULT_FIDELITY_PARAMS;
            auto err = FindFidelityParamsInApk(
                settings.default_fidelity_parameters_filename.c_str(),
                default_params);
            if (err != TUNINGFORK_ERROR_OK) return err;
            ALOGI("Using file %s for default params",
                  settings.default_fidelity_parameters_filename.c_str());
        }
    }
    auto training_params = GetTrainingParams(settings);
    StartFidelityParamDownloadThread(
        default_params, settings.c_settings.fidelity_params_callback,
        settings.initial_request_timeout_ms,
        settings.ultimate_request_timeout_ms);
    return TUNINGFORK_ERROR_OK;
}

TuningFork_ErrorCode KillDownloadThreads() {
    if (s_fp_thread.get() && s_fp_thread->joinable()) {
        s_kill_thread = true;
        s_fp_thread->join();
        s_fp_thread.reset();
        return TUNINGFORK_ERROR_OK;
    }
    return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
}

}  // namespace tuningfork

extern "C" {

using namespace tuningfork;

// Download FPs on a separate thread
TuningFork_ErrorCode TuningFork_startFidelityParamDownloadThread(
    const TuningFork_CProtobufSerialization* c_default_params,
    TuningFork_FidelityParamsCallback fidelity_params_callback) {
    if (c_default_params == nullptr) return TUNINGFORK_ERROR_BAD_PARAMETER;
    if (fidelity_params_callback == nullptr)
        return TUNINGFORK_ERROR_BAD_PARAMETER;
    const Settings* settings = GetSettings();
    if (settings == nullptr) return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    return StartFidelityParamDownloadThread(
        ToProtobufSerialization(*c_default_params), fidelity_params_callback,
        settings->initial_request_timeout_ms,
        settings->ultimate_request_timeout_ms);
}

// Load fidelity params from assets/tuningfork/<filename>
// Ownership of serializations is passed to the caller: call
//  TuningFork_CProtobufSerialization_free to deallocate any memory.
TuningFork_ErrorCode TuningFork_findFidelityParamsInApk(
    JNIEnv* env, jobject context, const char* filename,
    TuningFork_CProtobufSerialization* c_fps) {
    if (c_fps == nullptr) return TUNINGFORK_ERROR_BAD_PARAMETER;
    gamesdk::jni::Init(env, context);
    ProtobufSerialization fps;
    auto err = FindFidelityParamsInApk(filename, fps);
    if (err != TUNINGFORK_ERROR_OK) return err;
    ToCProtobufSerialization(fps, *c_fps);
    return TUNINGFORK_ERROR_OK;
}

TuningFork_ErrorCode TuningFork_setUploadCallback(
    TuningFork_UploadCallback cbk) {
    return tuningfork::SetUploadCallback(cbk);
}

TuningFork_ErrorCode TuningFork_saveOrDeleteFidelityParamsFile(
    JNIEnv* env, jobject context,
    const TuningFork_CProtobufSerialization* fps) {
    gamesdk::jni::Init(env, context);
    if (fps) {
        if (SaveFidelityParams(ToProtobufSerialization(*fps)))
            return TUNINGFORK_ERROR_OK;
    } else {
        std::string save_filename;
        if (GetSavedFileName(save_filename)) {
            if (file_utils::DeleteFile(save_filename))
                return TUNINGFORK_ERROR_OK;
        }
    }
    return TUNINGFORK_ERROR_COULDNT_SAVE_OR_DELETE_FPS;
}

}  // extern "C"
