
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

#include "ultimate_uploader.h"

#define LOG_TAG "TuningFork.GE"
#include "Log.h"

namespace tuningfork {

constexpr Duration kUploadCheckInterval = std::chrono::seconds(1);

const char kUploadRpcName[] = ":uploadTelemetry";

UltimateUploader::UltimateUploader(const TuningFork_Cache* persister,
                                   const HttpRequest& request)
    : Runnable(nullptr), persister_(persister), request_(request) {}
Duration UltimateUploader::DoWork() {
    CheckUploadPending();
    return kUploadCheckInterval;
}

void UltimateUploader::Run() { Runnable::Run(); }

bool UltimateUploader::CheckUploadPending() {
    TuningFork_CProtobufSerialization uploading_hists_ser;
    if (persister_->get(HISTOGRAMS_UPLOADING, &uploading_hists_ser,
                        persister_->user_data) == TUNINGFORK_ERROR_OK) {
        std::string request_json = ToString(uploading_hists_ser);
        int response_code = -1;
        std::string body;
        ALOGV("Got UPLOADING histograms: %s", request_json.c_str());
        TuningFork_ErrorCode ret =
            request_.Send(kUploadRpcName, request_json, response_code, body);
        if (ret == TUNINGFORK_ERROR_OK) {
            ALOGI("UPLOAD request returned %d %s", response_code, body.c_str());
            if (response_code == 200) {
                persister_->remove(HISTOGRAMS_UPLOADING, persister_->user_data);
                TuningFork_CProtobufSerialization_free(&uploading_hists_ser);
                return true;
            }
        } else {
            ALOGW("Error %d when sending UPLOAD request\n%s", ret,
                  request_json.c_str());
            persister_->remove(HISTOGRAMS_UPLOADING, persister_->user_data);
            persister_->set(HISTOGRAMS_PAUSED, &uploading_hists_ser,
                            persister_->user_data);
        }
        TuningFork_CProtobufSerialization_free(&uploading_hists_ser);
    } else {
        ALOGV("No upload pending");
        return true;
    }
    return false;
}

}  // namespace tuningfork
