// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once

enum class ABufferAccess {
    ShaderStorage,
    ShaderStorageAndPointers,
    FrameReuse
};

// Call with GL declarations in scope. The barrier describes the NEXT access
// to the shared fragment buffer, pointer image, and atomic counter.
inline void synchronizeABuffer(ABufferAccess access)
{
    GLbitfield barriers = GL_SHADER_STORAGE_BARRIER_BIT;
    if (access != ABufferAccess::ShaderStorage)
        barriers |= GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
    if (access == ABufferAccess::FrameReuse)
        barriers |= GL_BUFFER_UPDATE_BARRIER_BIT;
    glMemoryBarrier(barriers);
}

template<typename DrawResolve>
inline void drawABufferResolve(DrawResolve&& drawResolve)
{
    drawResolve();
    // The resolve shader clears list heads. Publish those clears and finish
    // fragment-buffer reads before the next TA pass reuses the same storage.
    synchronizeABuffer(ABufferAccess::ShaderStorageAndPointers);
}
