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

#include "swappy/swappy_common.h"
#include "tuningfork/tuningfork.h"
#include "tuningfork_internal.h"

// For versions of Swappy < 1.5, the types of times were longs.
typedef void (*SwappyPostSwapBuffersCallbackPre1_5)(
    void*, long desiredPresentationTimeMillis);
typedef void (*SwappyStartFrameCallbackPre1_5)(
    void*, int currentFrame, long desiredPresentationTimeMillis);
typedef void (*SwappyPostWaitCallbackPre1_5)(void*, long cpu_time_ns,
                                             long gpu_time_ns);
struct SwappyTracerPre1_5 {
  SwappyPreWaitCallback preWait = nullptr;
  SwappyPostWaitCallbackPre1_5 postWait = nullptr;
  SwappyPreSwapBuffersCallback preSwapBuffers = nullptr;
  SwappyPostSwapBuffersCallbackPre1_5 postSwapBuffers = nullptr;
  SwappyStartFrameCallbackPre1_5 startFrame = nullptr;
  void* userData = nullptr;
  SwappySwapIntervalChangedCallback swapIntervalChanged = nullptr;
};
constexpr int SWAPPY_VERSION_1_5 = ((1 << 16) | 5);

// For versions of Swappy < 1.3, the post-wait callback didn't give gpuTime
// and types of times were longs.
typedef SwappyPreWaitCallback SwappyWaitCallback;
struct SwappyTracerPre1_3 {
  SwappyWaitCallback preWait = nullptr;
  SwappyWaitCallback postWait = nullptr;
  SwappyPreSwapBuffersCallback preSwapBuffers = nullptr;
  SwappyPostSwapBuffersCallbackPre1_5 postSwapBuffers = nullptr;
  SwappyStartFrameCallbackPre1_5 startFrame = nullptr;
  void* userData = nullptr;
  SwappySwapIntervalChangedCallback swapIntervalChanged = nullptr;
};
constexpr int SWAPPY_VERSION_1_3 = ((1 << 16) | 3);

namespace tuningfork {

// This encapsulates the callbacks that are passed to Swappy at initialization,
// if it is
//  enabled + available.
class SwappyTraceWrapper {
  SwappyTracerFn swappyTracerFn_ = nullptr;
  SwappyTracer trace_ = {};
  TuningFork_TraceHandle logicTraceHandle_ = 0;

 public:
  SwappyTraceWrapper(const Settings& settings);
  // Swappy trace callbacks
  static void StartFrameCallback(void* userPtr, int /*currentFrame*/,
                                 int64_t /*desiredPresentationTimeMillis*/);
  static void PreWaitCallback(void* userPtr);
  static void PostWaitCallback(void* userPtr, int64_t cpu_time_ns,
                               int64_t gpu_time_ns);
  static void PreSwapBuffersCallback(void* userPtr);
  static void PostSwapBuffersCallback(
      void* userPtr, int64_t /*desiredPresentationTimeMillis*/);

  static void StartFrameCallbackPre1_5(void* userPtr, int /*currentFrame*/,
                                       long /*desiredPresentationTimeMillis*/);
  static void PostWaitCallbackPre1_5(void* userPtr, long cpuTimeNs,
                                     long gpuTimeNs);
  static void PostSwapBuffersCallbackPre1_5(
      void* userPtr, long /*desiredPresentationTimeMillis*/);

  static void StartFrameCallbackPre1_3(void* userPtr, int /*currentFrame*/,
                                       long /*desiredPresentationTimeMillis*/);
  static void PreWaitCallbackPre1_3(void* userPtr);
  static void PostWaitCallbackPre1_3(void* userPtr);
};

}  // namespace tuningfork
