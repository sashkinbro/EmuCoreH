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

#include "uploadthread.h"

#include <cstring>
#include <sstream>

#include "http_backend/http_request.h"
#include "http_backend/json_serializer.h"
#include "modp_b64.h"
#include "proto/protobuf_util.h"

#define LOG_TAG "TuningFork"
#include "Log.h"
#include "tuningfork_impl.h"

namespace tuningfork {

class DebugBackend : public IBackend {
   public:
    TuningFork_ErrorCode UploadTelemetry(const std::string& s) override {
        if (s.size() == 0) return TUNINGFORK_ERROR_BAD_PARAMETER;
        // Split the serialization into <128-byte chunks to avoid logcat line
        //  truncation.
        constexpr size_t maxStrLen = 128;
        int n = (s.size() + maxStrLen - 1) / maxStrLen;  // Round up
        for (int i = 0, j = 0; i < n; ++i) {
            std::stringstream str;
            str << "(TJS" << (i + 1) << "/" << n << ")";
            int m = std::min(s.size() - j, maxStrLen);
            str << s.substr(j, m);
            j += m;
            ALOGI("%s", str.str().c_str());
        }
        return TUNINGFORK_ERROR_OK;
    }
    TuningFork_ErrorCode GenerateTuningParameters(
        HttpRequest& request, const ProtobufSerialization* training_mode_fps,
        ProtobufSerialization& fidelity_params,
        std::string& experiment_id) override {
        return TUNINGFORK_ERROR_OK;
    }

    TuningFork_ErrorCode UploadDebugInfo(HttpRequest& request) override {
        return TUNINGFORK_ERROR_OK;
    }

    void Stop() override {}
};

static std::unique_ptr<DebugBackend> s_debug_backend =
    std::make_unique<DebugBackend>();

UploadThread::UploadThread(IdProvider* id_provider)
    : Runnable(nullptr),
      backend_(s_debug_backend.get()),
      upload_callback_(nullptr),
      persister_(nullptr),
      id_provider_(id_provider) {
    Start();
}

void UploadThread::SetBackend(IBackend* backend) {
    if (backend == nullptr)
        backend_ = s_debug_backend.get();
    else
        backend_ = backend;
}

UploadThread::~UploadThread() { Stop(); }

void UploadThread::Start() {
    ready_ = nullptr;
    Runnable::Start();
}

Duration UploadThread::DoWork() {
    if (ready_) {
        std::string evt_ser_json;
        JsonSerializer serializer(*ready_, id_provider_);
        serializer.SerializeEvent(RequestInfo::CachedValue(), evt_ser_json);
        if (upload_callback_) {
            upload_callback_(evt_ser_json.c_str(), evt_ser_json.size());
        }
        if (upload_)
            backend_->UploadTelemetry(evt_ser_json);
        else {
            TuningFork_CProtobufSerialization cser;
            ToCProtobufSerialization(evt_ser_json, cser);
            if (persister_)
                persister_->set(HISTOGRAMS_PAUSED, &cser,
                                persister_->user_data);
            TuningFork_CProtobufSerialization_free(&cser);
        }
        ready_ = nullptr;
    }
    if (!lifecycle_event_.empty()) {
        std::string evt_ser_json;
        JsonSerializer serializer(*lifecycle_event_session_, id_provider_);
        serializer.SerializeLifecycleEvent(
            lifecycle_event_.back(), RequestInfo::CachedValue(), evt_ser_json);
        if (upload_callback_) {
            upload_callback_(evt_ser_json.c_str(), evt_ser_json.size());
        }
        backend_->UploadTelemetry(evt_ser_json);
        lifecycle_event_.pop_back();
        lifecycle_event_session_ = nullptr;
    }
    return std::chrono::seconds(1);
}

// Returns true if we submitted, false if we are waiting for a previous submit
// to complete
bool UploadThread::Submit(const Session* session, bool upload) {
    if (ready_ == nullptr) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            upload_ = upload;
            ready_ = session;
        }
        cv_.notify_one();
        return true;
    } else
        return false;
}

void UploadThread::InitialChecks(Session& session, IdProvider& id_provider,
                                 const TuningFork_Cache* persister) {
    persister_ = persister;
    if (!persister_) {
        ALOGE("No persistence mechanism given");
        return;
    }
    // Check for PAUSED session
    TuningFork_CProtobufSerialization paused_hists_ser;
    if (persister->get(HISTOGRAMS_PAUSED, &paused_hists_ser,
                       persister_->user_data) == TUNINGFORK_ERROR_OK) {
        std::string paused_hists_str = ToString(paused_hists_ser);
        ALOGI("Got PAUSED histograms: %s", paused_hists_str.c_str());
        JsonSerializer::DeserializeAndMerge(paused_hists_str, id_provider,
                                            session);
        TuningFork_CProtobufSerialization_free(&paused_hists_ser);
    } else {
        ALOGI("No PAUSED histograms");
    }
}

bool UploadThread::SendLifecycleEvent(const LifecycleUploadEvent& event,
                                      const Session* session) {
    if (lifecycle_event_.empty()) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            lifecycle_event_.push_back(event);
            lifecycle_event_session_ = session;
        }
        cv_.notify_one();
        return true;
    } else
        return false;
    return true;
}

}  // namespace tuningfork
