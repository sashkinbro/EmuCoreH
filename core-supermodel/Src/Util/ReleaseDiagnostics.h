// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <stdio.h>
// Only diagnostic console output belongs here; file serialization uses stdio directly.
#if defined(MODEL3_RELEASE_SILENT_LOGS)
#define MODEL3_DIAGNOSTIC_PRINTF(...) ((void)0)
#define MODEL3_DIAGNOSTIC_PUTS(...) ((void)0)
#define MODEL3_DIAGNOSTIC_PUTCHAR(...) ((void)0)
#define MODEL3_DIAGNOSTIC_FPRINTF(...) ((void)0)
#else
#define MODEL3_DIAGNOSTIC_PRINTF(...) printf(__VA_ARGS__)
#define MODEL3_DIAGNOSTIC_PUTS(...) puts(__VA_ARGS__)
#define MODEL3_DIAGNOSTIC_PUTCHAR(...) putchar(__VA_ARGS__)
#define MODEL3_DIAGNOSTIC_FPRINTF(...) fprintf(__VA_ARGS__)
#endif
