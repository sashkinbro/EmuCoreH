// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

/**
 * Flycast-style renderer fallback rule: when a hardware context cannot be
 * initialized, a session running on Vulkan is rebooted on OpenGL once. Every
 * other renderer (and a second Vulkan failure) is reported to the user
 * instead, so a broken driver can never cause an endless restart loop.
 */
internal object RendererFallbackPolicy {

    /** Android renderer to relaunch on, or null when no fallback applies. */
    fun fallbackTarget(activeCoreRenderer: Int, alreadyAttempted: Boolean): Int? = when {
        alreadyAttempted -> null
        activeCoreRenderer == RendererDefaults.CORE_VULKAN -> RendererDefaults.OPENGL
        else -> null
    }
}
