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

#pragma once

#include <stdint.h>

#include <chrono>

#include "proto/protobuf_util.h"
#include "tuningfork/tuningfork.h"
#include "tuningfork/tuningfork_extra.h"

// Common type definitions

namespace tuningfork {

// The instrumentation key identifies a tick point within a frame or a trace
// segment
typedef uint16_t InstrumentationKey;
typedef uint32_t AnnotationId;
typedef uint64_t TraceHandle;
typedef uint64_t LoadingHandle;
typedef uint16_t LoadingTimeMetadataId;
typedef ProtobufSerialization SerializedAnnotation;
typedef TuningFork_LoadingTimeMetadata LoadingTimeMetadata;

typedef std::chrono::steady_clock::time_point TimePoint;
typedef std::chrono::steady_clock::duration Duration;
typedef std::chrono::system_clock::time_point SystemTimePoint;
typedef std::chrono::system_clock::duration SystemDuration;

}  // namespace tuningfork
