// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RendererFallbackPolicyTest {

    @Test
    fun `vulkan falls back to opengl on first failure`() {
        assertEquals(
            RendererDefaults.OPENGL,
            RendererFallbackPolicy.fallbackTarget(
                activeCoreRenderer = RendererDefaults.CORE_VULKAN,
                alreadyAttempted = false
            )
        )
    }

    @Test
    fun `vulkan does not fall back twice`() {
        assertNull(
            RendererFallbackPolicy.fallbackTarget(
                activeCoreRenderer = RendererDefaults.CORE_VULKAN,
                alreadyAttempted = true
            )
        )
    }

    @Test
    fun `opengl failure is terminal`() {
        assertNull(
            RendererFallbackPolicy.fallbackTarget(
                activeCoreRenderer = RendererDefaults.CORE_OPENGL,
                alreadyAttempted = false
            )
        )
        assertNull(
            RendererFallbackPolicy.fallbackTarget(
                activeCoreRenderer = RendererDefaults.CORE_OPENGL,
                alreadyAttempted = true
            )
        )
    }

    @Test
    fun `software failure is terminal`() {
        assertNull(
            RendererFallbackPolicy.fallbackTarget(
                activeCoreRenderer = RendererDefaults.CORE_SOFTWARE,
                alreadyAttempted = false
            )
        )
    }

    @Test
    fun `fallback target maps to the opengl core renderer`() {
        val target = RendererFallbackPolicy.fallbackTarget(
            activeCoreRenderer = RendererDefaults.CORE_VULKAN,
            alreadyAttempted = false
        )
        assertEquals(RendererDefaults.OPENGL, target)
        assertEquals(RendererDefaults.CORE_OPENGL, RendererDefaults.toCoreRenderer(target!!))
        assertEquals(RendererDefaults.OPENGL, RendererDefaults.normalizeAndroidRenderer(target))
    }

    /**
     * A live switch reboots on the renderer the running session presents on,
     * so that mapping has to survive the round trip for every backend.
     */
    @Test
    fun `core renderer maps back to the android renderer in use`() {
        assertEquals(RendererDefaults.VULKAN, RendererDefaults.androidRendererForCore(RendererDefaults.CORE_VULKAN))
        assertEquals(RendererDefaults.OPENGL, RendererDefaults.androidRendererForCore(RendererDefaults.CORE_OPENGL))
        assertEquals(
            RendererDefaults.SOFTWARE,
            RendererDefaults.androidRendererForCore(RendererDefaults.CORE_SOFTWARE)
        )
        RendererDefaults.OPENGL.let { android ->
            val core = RendererDefaults.toCoreRenderer(android)
            assertEquals(android, RendererDefaults.androidRendererForCore(core))
        }
        RendererDefaults.VULKAN.let { android ->
            val core = RendererDefaults.toCoreRenderer(android)
            assertEquals(android, RendererDefaults.androidRendererForCore(core))
        }
    }
}
