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

#include <cstdlib>
#include <cstring>

#include "jni/jni_helper.h"
#include "proto/protobuf_util.h"
#include "settings.h"
#include "tuningfork/tuningfork.h"
#include "tuningfork/tuningfork_extra.h"
#include "tuningfork_internal.h"
#include "tuningfork_utils.h"

extern "C" {

namespace tf = tuningfork;
namespace jni = gamesdk::jni;

void TUNINGFORK_VERSION_SYMBOL();

TuningFork_ErrorCode TuningFork_init(const TuningFork_Settings *c_settings_in,
                                     JNIEnv *env, jobject context) {
    TUNINGFORK_VERSION_SYMBOL();
    tf::Settings settings{};
    if (c_settings_in != nullptr) {
        settings.c_settings = *c_settings_in;
    }
    jni::Init(env, context);
    tf::g_verbose_logging_enabled = settings.c_settings.verbose_logging_enabled;
    bool first_run = tf::CheckIfFirstRun();
    TuningFork_ErrorCode err = tf::Settings::FindInApk(&settings);
    if (err != TUNINGFORK_ERROR_OK) return err;
    settings.Check();
    err = tf::Init(settings, nullptr, nullptr, nullptr, nullptr, nullptr,
                   first_run);
    if (err != TUNINGFORK_ERROR_OK) return err;
    if (!(settings.default_fidelity_parameters_filename.empty() &&
          settings.c_settings.training_fidelity_params == nullptr)) {
        err = GetDefaultsFromAPKAndDownloadFPs(settings);
    }
    return err;
}

// Blocking call to get fidelity parameters from the server.
// Note that once fidelity parameters are downloaded, any timing information is
// recorded
//  as being associated with those parameters.
TuningFork_ErrorCode TuningFork_getFidelityParameters(
    const TuningFork_CProtobufSerialization *default_params,
    TuningFork_CProtobufSerialization *params, uint32_t timeout_ms) {
    tf::ProtobufSerialization defaults;
    if (default_params) defaults = tf::ToProtobufSerialization(*default_params);
    tf::ProtobufSerialization s;
    TuningFork_ErrorCode result =
        tf::GetFidelityParameters(defaults, s, timeout_ms);
    if (result == TUNINGFORK_ERROR_OK && params)
        tf::ToCProtobufSerialization(s, *params);
    return result;
}

// Protobuf serialization of the current annotation
TuningFork_ErrorCode TuningFork_setCurrentAnnotation(
    const TuningFork_CProtobufSerialization *annotation) {
    if (annotation != nullptr)
        return tf::SetCurrentAnnotation(
            tf::ToProtobufSerialization(*annotation));
    else
        return TUNINGFORK_ERROR_INVALID_ANNOTATION;
}

// Record a frame tick that will be associated with the instrumentation key and
// the current
//   annotation
TuningFork_ErrorCode TuningFork_frameTick(TuningFork_InstrumentKey id) {
    return tf::FrameTick(id);
}

// Record a frame tick using an external time, rather than system time
TuningFork_ErrorCode TuningFork_frameDeltaTimeNanos(TuningFork_InstrumentKey id,
                                                    TuningFork_Duration dt) {
    return tf::FrameDeltaTimeNanos(id, std::chrono::nanoseconds(dt));
}

// Start a trace segment
TuningFork_ErrorCode TuningFork_startTrace(TuningFork_InstrumentKey key,
                                           TuningFork_TraceHandle *handle) {
    if (handle == nullptr) return TUNINGFORK_ERROR_INVALID_TRACE_HANDLE;
    return tf::StartTrace(key, *handle);
}

// Record a trace with the key and annotation set using startTrace
TuningFork_ErrorCode TuningFork_endTrace(TuningFork_TraceHandle h) {
    return tf::EndTrace(h);
}

TuningFork_ErrorCode TuningFork_flush() { return tf::Flush(true); }

TuningFork_ErrorCode TuningFork_destroy() {
    tf::KillDownloadThreads();
    return tf::Destroy();
}

TuningFork_ErrorCode TuningFork_setFidelityParameters(
    const TuningFork_CProtobufSerialization *params) {
    if (params != nullptr)
        return tf::SetFidelityParameters(tf::ToProtobufSerialization(*params));
    else
        return TUNINGFORK_ERROR_BAD_PARAMETER;
}

TuningFork_ErrorCode TuningFork_enableMemoryRecording(bool enable) {
    return tf::EnableMemoryRecording(enable);
}

bool TuningFork_isFrameTimeLoggingPaused() {
    return tf::IsFrameTimeLoggingPaused();
}

TuningFork_ErrorCode TuningFork_pauseFrameTimeLogging() {
    return tf::PauseFrameTimeLogging();
}

TuningFork_ErrorCode TuningFork_resumeFrameTimeLogging() {
    return tf::ResumeFrameTimeLogging();
}

// Take the C metadata structure passed in and copy to the C++ structure,
// taking into account any version changes indicated by changes in the size.
// Currently tf::LoadingTimeMetadata is typedefed to
// TuningFork_LoadingTimeMetadata so this does nothing.
static TuningFork_ErrorCode CheckLoadingMetaData(
    const TuningFork_LoadingTimeMetadata *eventMetadata_in,
    uint32_t eventMetadataSize, tf::LoadingTimeMetadata &eventMetadata) {
    if (eventMetadata_in == nullptr ||
        eventMetadataSize != sizeof(TuningFork_LoadingTimeMetadata))
        return TUNINGFORK_ERROR_BAD_PARAMETER;
    eventMetadata = *eventMetadata_in;
    return TUNINGFORK_ERROR_OK;
}

TuningFork_ErrorCode TuningFork_recordLoadingTime(
    uint64_t time_ns, const TuningFork_LoadingTimeMetadata *eventMetadata_in,
    uint32_t eventMetadataSize,
    const TuningFork_CProtobufSerialization *annotation) {
    tf::LoadingTimeMetadata eventMetadata;
    auto err = CheckLoadingMetaData(eventMetadata_in, eventMetadataSize,
                                    eventMetadata);
    if (err != TUNINGFORK_ERROR_OK) return err;
    return tf::RecordLoadingTime(std::chrono::nanoseconds(time_ns),
                                 eventMetadata,
                                 tf::ToProtobufSerialization(*annotation));
}

TuningFork_ErrorCode TuningFork_startRecordingLoadingTime(
    const TuningFork_LoadingTimeMetadata *eventMetadata_in,
    uint32_t eventMetadataSize,
    const TuningFork_CProtobufSerialization *annotation,
    TuningFork_LoadingEventHandle *handle) {
    tf::LoadingTimeMetadata eventMetadata;
    auto err = CheckLoadingMetaData(eventMetadata_in, eventMetadataSize,
                                    eventMetadata);
    if (err != TUNINGFORK_ERROR_OK) {
        return err;
    }
    if (handle == nullptr) return TUNINGFORK_ERROR_INVALID_LOADING_HANDLE;
    return tf::StartRecordingLoadingTime(
        eventMetadata, tf::ToProtobufSerialization(*annotation), *handle);
}

TuningFork_ErrorCode TuningFork_stopRecordingLoadingTime(
    TuningFork_LoadingEventHandle handle) {
    return tf::StopRecordingLoadingTime(handle);
}

TuningFork_ErrorCode TuningFork_reportLifecycleEvent(
    TuningFork_LifecycleState state) {
    return tf::ReportLifecycleEvent(state);
}

TuningFork_ErrorCode TuningFork_startLoadingGroup(
    const TuningFork_LoadingTimeMetadata *eventMetadata_in,
    uint32_t eventMetadataSize,
    const TuningFork_CProtobufSerialization *annotation_in,
    TuningFork_LoadingGroupHandle *handle) {
    tf::LoadingTimeMetadata eventMetadata;
    tf::LoadingTimeMetadata *eventMetadataPtr = nullptr;
    if (eventMetadata_in != nullptr) {
        auto err = CheckLoadingMetaData(eventMetadata_in, eventMetadataSize,
                                        eventMetadata);
        if (err != TUNINGFORK_ERROR_OK) {
            return err;
        }
        eventMetadataPtr = &eventMetadata;
    }
    tf::ProtobufSerialization annotation;
    tf::ProtobufSerialization *annotationPtr = nullptr;
    if (annotation_in != nullptr) {
        annotation = tf::ToProtobufSerialization(*annotation_in);
        annotationPtr = &annotation;
    }
    return tf::StartLoadingGroup(eventMetadataPtr, annotationPtr, handle);
}

TuningFork_ErrorCode TuningFork_stopLoadingGroup(
    TuningFork_LoadingGroupHandle handle) {
    return tf::StopLoadingGroup(handle);
}

void TUNINGFORK_VERSION_SYMBOL() {
    // Intentionally empty: this function is used to ensure that the proper
    // version of the library is linked against the proper headers.
    // In case of mismatch, a linker error will be triggered because of an
    // undefined symbol, as the name of the function depends on the version.
}

const char *Tuningfork_versionString() {
  static const char version[] =
      AGDK_STRING_VERSION(TUNINGFORK_MAJOR_VERSION, TUNINGFORK_MINOR_VERSION,
                          TUNINGFORK_BUGFIX_VERSION);
  return version;
}

TuningFork_ErrorCode TuningFork_setAggregationStrategyInterval(
    TuningFork_Submission method, uint32_t interval_ms_or_count) {
    return tf::SetAggregationStrategyInterval(method, interval_ms_or_count);
}

}  // extern "C" {
