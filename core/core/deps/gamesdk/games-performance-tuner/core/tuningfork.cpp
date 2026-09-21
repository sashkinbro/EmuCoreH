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

#include <atomic>
#include <chrono>
#include <cinttypes>
#include <map>
#include <memory>
#include <sstream>
#include <vector>

#include "tuningfork_impl.h"
#include "tuningfork_internal.h"

#define LOG_TAG "TuningFork"
#include "Log.h"
#include "annotation_util.h"
#include "histogram.h"
#include "memory_telemetry.h"
#include "metric.h"
#include "tuningfork_utils.h"

/* Annotations come into tuning fork as a serialized protobuf. The protobuf can
 * only have enums in it. We form an integer annotation id from the annotation
 * interpreted as a mixed-radix number. E.g. say we have the following in the
 * proto: enum A { A_1 = 1, A_2 = 2, A_3 = 3}; enum B { B_1 = 1, B_2 = 2}; enum
 * C { C_1 = 1}; message Annotation { optional A a = 1; optional B b = 2;
 * optional C c = 3}; Then a serialization of 'b : B_1' might be: 0x16 0x01
 * https://developers.google.com/protocol-buffers/docs/encoding
 * Note the shift of 3 bits for the key.
 *
 * Assume we have 2 possible instrumentation keys: NUM_IKEY = 2
 *
 * The annotation id then is (0 + 1*4 + 0)*NUM_IKEY = 8, where the factor of 4
 * comes from the radix associated with 'a', i.e. 3 values for enum A + option
 * missing
 *
 * A compound id is formed from the annotation id and the instrument key:
 * compound_id = annotation_id + instrument_key;
 *
 * So for instrument key 1, the compound_id with the above annotation is 9
 *
 * This compound_id is used to look up a histogram in the Session.
 *
 * annotation_radix_mult_ stores the multiplied radixes, so for the above, it
 * is: [4 4*3 4*3*2] = [4 12 24] and the maximum number of annotations is 24
 *
 * */

namespace tuningfork {

static std::unique_ptr<TuningForkImpl> s_impl;
static std::unique_ptr<SwappyTraceWrapper> s_swappy_tracer;

// False by default to hide sensitive information in the logs
bool g_verbose_logging_enabled = false;

TuningFork_ErrorCode Init(const Settings &settings,
                          const RequestInfo *request_info, IBackend *backend,
                          ITimeProvider *time_provider,
                          IMemInfoProvider *meminfo_provider,
                          IBatteryProvider *battery_provider, bool first_run) {
    if (s_impl.get() != nullptr) return TUNINGFORK_ERROR_ALREADY_INITIALIZED;

    if (request_info != nullptr) {
        RequestInfo::CachedValue() = *request_info;
    } else {
        RequestInfo::CachedValue() =
            RequestInfo::ForThisGameAndDevice(settings);
    }

    s_impl = std::make_unique<TuningForkImpl>(settings, backend, time_provider,
                                              meminfo_provider,
                                              battery_provider, first_run);

    if (s_impl->InitializationErrorCode() != TUNINGFORK_ERROR_OK) {
        auto err = s_impl->InitializationErrorCode();
        s_impl.reset();
        return err;
    }

    // Set up the Swappy tracer after TuningFork is initialized
    if (settings.c_settings.swappy_tracer_fn != nullptr) {
        s_swappy_tracer = std::unique_ptr<SwappyTraceWrapper>(
            new SwappyTraceWrapper(settings));
    }
    return TUNINGFORK_ERROR_OK;
}

TuningFork_ErrorCode GetFidelityParameters(
    const ProtobufSerialization &defaultParams, ProtobufSerialization &params,
    uint32_t timeout_ms) {
    if (!s_impl) {
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    } else {
        return s_impl->GetFidelityParameters(defaultParams, params, timeout_ms);
    }
}

TuningFork_ErrorCode FrameTick(InstrumentationKey id) {
    if (!s_impl) {
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    } else {
        return s_impl->FrameTick(id);
    }
}

TuningFork_ErrorCode FrameDeltaTimeNanos(InstrumentationKey id, Duration dt) {
    if (!s_impl) {
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    } else {
        return s_impl->FrameDeltaTimeNanos(id, dt);
    }
}

TuningFork_ErrorCode StartTrace(InstrumentationKey key, TraceHandle &handle) {
    if (!s_impl) {
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    } else {
        return s_impl->StartTrace(key, handle);
    }
}

TuningFork_ErrorCode EndTrace(TraceHandle h) {
    if (!s_impl) {
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    } else {
        return s_impl->EndTrace(h);
    }
}

TuningFork_ErrorCode SetCurrentAnnotation(const ProtobufSerialization &ann) {
    if (!s_impl) {
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    } else {
        if (s_impl->SetCurrentAnnotation(ann).detail.type ==
            Metric::Type::ERROR) {
            return TUNINGFORK_ERROR_INVALID_ANNOTATION;
        } else {
            return TUNINGFORK_ERROR_OK;
        }
    }
}

TuningFork_ErrorCode SetUploadCallback(TuningFork_UploadCallback cbk) {
    if (!s_impl) {
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    } else {
        s_impl->SetUploadCallback(cbk);
        return TUNINGFORK_ERROR_OK;
    }
}

TuningFork_ErrorCode Flush(bool upload) {
    if (!s_impl) {
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    } else {
        return s_impl->Flush(upload);
    }
}

TuningFork_ErrorCode Destroy() {
    if (!s_impl) {
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    } else {
        s_impl.reset();
        return TUNINGFORK_ERROR_OK;
    }
}

const Settings *GetSettings() {
    if (!s_impl)
        return nullptr;
    else
        return &s_impl->GetSettings();
}

TuningFork_ErrorCode SetFidelityParameters(
    const ProtobufSerialization &params) {
    if (!s_impl)
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    else
        return s_impl->SetFidelityParameters(params);
}

TuningFork_ErrorCode EnableMemoryRecording(bool enable) {
    if (!s_impl)
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    else
        return s_impl->EnableMemoryRecording(enable);
}

bool IsFrameTimeLoggingPaused() {
    if (!s_impl)
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    else
        return s_impl->IsFrameTimeLoggingPaused();
}

TuningFork_ErrorCode PauseFrameTimeLogging() {
    if (!s_impl)
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    else
        return s_impl->PauseFrameTimeLogging();
}

TuningFork_ErrorCode ResumeFrameTimeLogging() {
    if (!s_impl)
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    else
        return s_impl->ResumeFrameTimeLogging();
}

TuningFork_ErrorCode RecordLoadingTime(
    Duration duration, const LoadingTimeMetadata &d,
    const ProtobufSerialization &annotation) {
    if (!s_impl)
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    else
        return s_impl->RecordLoadingTime(duration, d, annotation,
                                         false /*relativeToStart*/);
}

TuningFork_ErrorCode StartRecordingLoadingTime(
    const LoadingTimeMetadata &d, const ProtobufSerialization &annotation,
    LoadingHandle &handle) {
    if (!s_impl)
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    else
        return s_impl->StartRecordingLoadingTime(d, annotation, handle);
}

TuningFork_ErrorCode StopRecordingLoadingTime(LoadingHandle handle) {
    if (!s_impl)
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    else
        return s_impl->StopRecordingLoadingTime(handle);
}
TuningFork_ErrorCode StartLoadingGroup(const LoadingTimeMetadata *d,
                                       const ProtobufSerialization *annotation,
                                       LoadingHandle *handle) {
    if (!s_impl)
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    else
        return s_impl->StartLoadingGroup(d, annotation, handle);
}

TuningFork_ErrorCode StopLoadingGroup(LoadingHandle handle) {
    if (!s_impl)
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    else
        return s_impl->StopLoadingGroup(handle);
}

TuningFork_ErrorCode ReportLifecycleEvent(TuningFork_LifecycleState state) {
    if (!s_impl)
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    else
        return s_impl->ReportLifecycleEvent(state);
}

bool CheckIfFirstRun() {
    if (!file_utils::FileExists(DefaultTuningForkSaveDirectory())) {
        file_utils::CheckAndCreateDir(DefaultTuningForkSaveDirectory());
        ALOGV("First run");
        return true;
    } else {
        ALOGV("Not first run");
        return false;
    }
}

std::string DefaultTuningForkSaveDirectory() {
    std::stringstream save_dir;
    // Try the app's cache dir or tmp storage.
    if (gamesdk::jni::IsValid())
        save_dir << file_utils::GetAppCacheDir();
    else
        save_dir << "/data/local/tmp";
    save_dir << "/tuningfork";
    return save_dir.str();
}

TuningFork_ErrorCode SetAggregationStrategyInterval(
    TuningFork_Submission method, uint32_t interval_ms_or_count) {
    if (!s_impl)
        return TUNINGFORK_ERROR_TUNINGFORK_NOT_INITIALIZED;
    else
        return s_impl->SetAggregationStrategyInterval(method,
                                                      interval_ms_or_count);
}

}  // namespace tuningfork
