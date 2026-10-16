// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
//
// Fatal-signal crash reporter. Installs async-signal-safe handlers for the
// deadly signals (SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE, SIGTRAP) that
// append a full report (signal, fault address, thread, backtrace) to the
// crash log file right before the process dies. The previous handler is
// chained afterwards so the platform still captures the tombstone. Nothing
// is ever written unless the process actually crashes.
#pragma once

namespace emucoreh::crash {

// Installs the handlers once; later calls are ignored. [log_path] is the
// crash.log file owned by CrashLogger on the Kotlin side.
void InstallCrashReporter(const char* log_path);

}  // namespace emucoreh::crash
