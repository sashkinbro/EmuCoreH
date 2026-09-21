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

#pragma once

#include <android/choreographer.h>

// Declare the types, even if we can't access the functions.
#if 0 //__ANDROID_API__ < 24

struct AChoreographer;
typedef struct AChoreographer AChoreographer;

/**
 * Prototype of the function that is called when a new frame is being rendered.
 * It's passed the time that the frame is being rendered as nanoseconds in the
 * CLOCK_MONOTONIC time base, as well as the data pointer provided by the
 * application that registered a callback. All callbacks that run as part of
 * rendering a frame will observe the same frame time, so it should be used
 * whenever events need to be synchronized (e.g. animations).
 */
typedef void (*AChoreographer_frameCallback)(long frameTimeNanos, void* data);

#endif  // __ANDROID_API__ < 24

#if __NDK_MAJOR__ < 22  // __ANDROID_API__ < 30

/**
 * Prototype of the function that is called when the display refresh rate
 * changes. It's passed the new vsync period in nanoseconds, as well as the data
 * pointer provided by the application that registered a callback.
 */
typedef void (*AChoreographer_refreshRateCallback)(int64_t vsyncPeriodNanos,
                                                   void* data);

#endif  // __ANDROID_API__ < 30

#if __NDK_MAJOR__ < 25  // __ANDROID_API__ < 33

struct AChoreographerFrameCallbackData;
/**
 * Opaque type that provides access to an AChoreographerFrameCallbackData
 * object, which contains various methods to extract frame information.
 */
typedef struct AChoreographerFrameCallbackData AChoreographerFrameCallbackData;

/**
 * Prototype of the function that is called when a new frame is being rendered.
 * It is called with \c callbackData describing multiple frame timelines, as
 * well as the \c data pointer provided by the application that registered a
 * callback. The \c callbackData does not outlive the callback.
 */
typedef void (*AChoreographer_vsyncCallback)(
    const AChoreographerFrameCallbackData* callbackData, void* data);

/**
 * Posts a callback to be run when the application should begin rendering the
 * next frame. The data pointer provided will be passed to the callback function
 * when it's called.
 */
typedef void (*AChoreographer_postVsyncCallback)(
    AChoreographer* choreographer, AChoreographer_vsyncCallback callback,
    void* data);

/**
 * Gets the index of the platform-preferred frame timeline.
 * The preferred frame timeline is the default
 * by which the platform scheduled the app, based on the device configuration.
 */
typedef size_t (
    *AChoreographerFrameCallbackData_getPreferredFrameTimelineIndex)(
    const AChoreographerFrameCallbackData* data);

/**
 * Gets the time in nanoseconds at which the frame described at the given \c
 * index is expected to be presented. This time should be used to advance any
 * animation clocks.
 */
typedef int64_t (
    *AChoreographerFrameCallbackData_getFrameTimelineExpectedPresentationTimeNanos)(
    const AChoreographerFrameCallbackData* data, size_t index);

/**
 * Gets the time in nanoseconds at which the frame described at the given \c
 * index needs to be ready by in order to be presented on time.
 *
 * Available since API level 33.
 *
 * \param index index of a frame timeline, in \f( [0, FrameTimelinesLength) \f).
 * See AChoreographerFrameCallbackData_getFrameTimelinesLength()
 */
typedef int64_t (
    *AChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos)(
    const AChoreographerFrameCallbackData* data, size_t index);

#endif  // __ANDROID_API__ < 33