// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: GPL-2.0-or-later
#include <cstdint>
#include <cstdio>

using GLbitfield = uint32_t;
constexpr GLbitfield GL_SHADER_IMAGE_ACCESS_BARRIER_BIT = 0x20;
constexpr GLbitfield GL_BUFFER_UPDATE_BARRIER_BIT = 0x200;
constexpr GLbitfield GL_SHADER_STORAGE_BARRIER_BIT = 0x2000;

static GLbitfield lastBarrier;
static unsigned barrierCalls;
static bool resolveIssued;
static bool barrierAfterResolve;
// Capture the real policy's GL API argument: these access domains are mandated
// by Khronos, and an image/API-update barrier cannot publish SSBO shader writes.
static void glMemoryBarrier(GLbitfield barriers)
{
    lastBarrier = barriers;
    ++barrierCalls;
    if (resolveIssued) barrierAfterResolve = true;
}

#include "../../../../core/core/rend/gl4/abuffer_sync.h"

int main()
{
    int failures = 0;
    auto check = [&](bool success, const char* name) {
        std::printf("%s %s\n", success ? "PASS" : "FAIL", name);
        failures += !success;
    };
    synchronizeABuffer(ABufferAccess::ShaderStorage);
    check(barrierCalls == 1 && (lastBarrier & GL_SHADER_STORAGE_BARRIER_BIT) != 0,
        "modifier volume pass publishes fragment SSBO updates to later shaders");
    synchronizeABuffer(ABufferAccess::ShaderStorageAndPointers);
    check(barrierCalls == 2 && (lastBarrier & GL_SHADER_STORAGE_BARRIER_BIT) != 0 &&
        (lastBarrier & GL_SHADER_IMAGE_ACCESS_BARRIER_BIT) != 0,
        "resolve sees both fragment SSBO data and linked list pointer image");
    const auto previousCalls = barrierCalls;
    synchronizeABuffer(ABufferAccess::FrameReuse);
    check(barrierCalls == previousCalls + 1 && (lastBarrier & GL_BUFFER_UPDATE_BARRIER_BIT) != 0,
        "next frame waits for shader atomic writes before API counter reset");
    check(barrierCalls == previousCalls + 1 && (lastBarrier & GL_SHADER_STORAGE_BARRIER_BIT) != 0 &&
        (lastBarrier & GL_SHADER_IMAGE_ACCESS_BARRIER_BIT) != 0,
        "frame reuse orders prior resolve reads and pointer clears before new writes");
    drawABufferResolve([&] { resolveIssued = true; });
    check(barrierAfterResolve && (lastBarrier & GL_SHADER_IMAGE_ACCESS_BARRIER_BIT) != 0 &&
        (lastBarrier & GL_SHADER_STORAGE_BARRIER_BIT) != 0,
        "intermediate TA resolve publishes pointer clears after drawing before next pass insertion");
    return failures == 0 ? 0 : 1;
}
