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

#include "core/common.h"
#include "metric.h"
#include "proto/protobuf_util.h"
#include "tuningfork/tuningfork.h"
#include "tuningfork_internal.h"

class AAsset;

namespace tuningfork {

struct LoadingTimeMetadataWithGroup;

// Interface to an object that can map between serializations and ids and create
// compound ids.
class IdProvider {
 public:
  virtual ~IdProvider() {}
  // Decode <ser> into an AnnotationId. If loading is non-null, it sets it
  // according to whether the annotation is a loading annotation.
  virtual TuningFork_ErrorCode SerializedAnnotationToAnnotationId(
      const ProtobufSerialization& ser, AnnotationId& id) = 0;

  // Return a new id that is made up of <annotation_id> and <k>.
  // Gives an error if the id is out-of-bounds.
  virtual TuningFork_ErrorCode MakeCompoundId(InstrumentationKey k,
                                              AnnotationId annotation_id,
                                              MetricId& id) = 0;

  virtual TuningFork_ErrorCode AnnotationIdToSerializedAnnotation(
      AnnotationId id, SerializedAnnotation& ser) = 0;

  virtual TuningFork_ErrorCode MetricIdToLoadingTimeMetadata(
      MetricId id, LoadingTimeMetadataWithGroup& md) = 0;
};

}  // namespace tuningfork
