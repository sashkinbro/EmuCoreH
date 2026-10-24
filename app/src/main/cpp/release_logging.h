// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
#pragma once

#include <android/log.h>
#include <atomic>

// Release diagnostics must not evaluate ordinary message arguments. A failed
// operation can recur each frame: report each error site once, then stay quiet.
#ifdef NDEBUG
#define EMUCOREH_LOG_INFO(tag, ...) ((void)0)
#define EMUCOREH_LOG_WARN(tag, ...) ((void)0)
#define EMUCOREH_LOG_ERROR(tag, ...) do { \
    static std::atomic_flag reported = ATOMIC_FLAG_INIT; \
    if (!reported.test_and_set(std::memory_order_relaxed)) \
        __android_log_print(ANDROID_LOG_ERROR, tag, __VA_ARGS__); \
} while (0)
#else
#define EMUCOREH_LOG_INFO(tag, ...) __android_log_print(ANDROID_LOG_INFO, tag, __VA_ARGS__)
#define EMUCOREH_LOG_WARN(tag, ...) __android_log_print(ANDROID_LOG_WARN, tag, __VA_ARGS__)
#define EMUCOREH_LOG_ERROR(tag, ...) __android_log_print(ANDROID_LOG_ERROR, tag, __VA_ARGS__)
#endif
