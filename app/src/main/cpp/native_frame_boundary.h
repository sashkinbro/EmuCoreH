// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
#pragma once

#include <exception>
#include <new>

namespace emucoreh {

// Renderer/context work runs on a JVM thread. Native exceptions must be
// reported through JNI so the Kotlin runtime can stop and display the failure.
template<typename Frame, typename ReportFailure>
bool RunNativeFrame(Frame&& frame, ReportFailure&& reportFailure) {
    try {
        frame();
        return true;
    } catch (const std::bad_alloc&) {
        // Use a literal: constructing an error string could allocate again
        // while the per-pixel renderer is already out of host memory.
        reportFailure("Native rendering ran out of memory");
    } catch (const std::exception& error) {
        reportFailure(error.what());
    } catch (...) {
        reportFailure("Native frame execution failed");
    }
    return false;
}

}  // namespace emucoreh
