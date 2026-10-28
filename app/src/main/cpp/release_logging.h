// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
#pragma once

#include <android/log.h>

// Release removes routine diagnostics and their arguments, but keeps failures.
#ifdef NDEBUG
#define EMUCOREH_LOG_INFO(tag, ...) ((void)0)
#else
#define EMUCOREH_LOG_INFO(tag, ...) __android_log_print(ANDROID_LOG_INFO, tag, __VA_ARGS__)
#endif
#define EMUCOREH_LOG_WARN(tag, ...) __android_log_print(ANDROID_LOG_WARN, tag, __VA_ARGS__)
#define EMUCOREH_LOG_ERROR(tag, ...) __android_log_print(ANDROID_LOG_ERROR, tag, __VA_ARGS__)
