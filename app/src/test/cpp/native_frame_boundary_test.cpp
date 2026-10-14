// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
#include "../../main/cpp/native_frame_boundary.h"

#include <cassert>
#include <cstdio>
#include <cstring>
#include <new>
#include <stdexcept>
#include <system_error>

template<typename Failure>
static bool FailureIsReported(Failure failure, const char* expectedMessage)
{
    bool reported = false;
    bool escaped = false;
    bool completed = true;
    bool laterFrameWork = false;
    try {
        completed = emucoreh::RunNativeFrame([&] {
            failure();
            laterFrameWork = true;
        }, [&](const char* message) {
            reported = expectedMessage == nullptr || std::strstr(message, expectedMessage) != nullptr;
        });
    } catch (...) {
        escaped = true;
    }
    return reported && !escaped && !completed && !laterFrameWork;
}

int main()
{
    int failures = 0;
    auto check = [&](bool success, const char* name) {
        std::printf("%s %s\n", success ? "PASS" : "FAIL", name);
        failures += !success;
    };
    int frames = 0;
    int errors = 0;
    check(emucoreh::RunNativeFrame([&] { ++frames; }, [&](const char*) { ++errors; }) &&
        frames == 1 && errors == 0, "successful frame executes normally without reporting failure");
    check(FailureIsReported([] { throw std::bad_alloc(); }, "memory"),
        "OIT host allocation failure is reported without crossing JNI");
    check(FailureIsReported([] { throw std::system_error(std::make_error_code(std::errc::not_enough_memory), "GPU allocation"); }, "GPU allocation"),
        "native driver error preserves its detail without crossing JNI");
    check(FailureIsReported([] { throw std::runtime_error("renderer context failed"); }, "renderer context failed"),
        "context initialization error is reported without continuing frame work");
    check(FailureIsReported([] { throw 7; }, "Native"),
        "unknown native exception is contained at frame boundary");
    return failures == 0 ? 0 : 1;
}
