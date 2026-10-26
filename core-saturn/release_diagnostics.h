// SPDX-License-Identifier: GPL-2.0-or-later
#ifndef SATURN_RELEASE_DIAGNOSTICS_H
#define SATURN_RELEASE_DIAGNOSTICS_H
#include <stdio.h>
#if defined(SATURN_RELEASE_SILENT_DIAGNOSTICS)
#define SATURN_DIAGNOSTIC_PRINTF(...) ((void)0)
#define SATURN_DIAGNOSTIC_FPRINTF(...) ((void)0)
#define SATURN_DIAGNOSTIC_PUTS(...) ((void)0)
#define SATURN_DIAGNOSTIC_PUTCHAR(...) ((void)0)
#else
#define SATURN_DIAGNOSTIC_PRINTF(...) printf(__VA_ARGS__)
#define SATURN_DIAGNOSTIC_FPRINTF(...) fprintf(__VA_ARGS__)
#define SATURN_DIAGNOSTIC_PUTS(...) puts(__VA_ARGS__)
#define SATURN_DIAGNOSTIC_PUTCHAR(...) putchar(__VA_ARGS__)
#endif
#endif
