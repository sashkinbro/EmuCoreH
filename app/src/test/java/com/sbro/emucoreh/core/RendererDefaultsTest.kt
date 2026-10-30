package com.sbro.emucoreh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererDefaultsTest {
    @Test
    fun model3DefaultsToGlesBecauseTheVulkanSceneIsSlower() {
        assertEquals(RendererDefaults.OPENGL, RendererDefaults.MODEL3_DEFAULT)
        assertEquals(RendererDefaults.OPENGL, RendererDefaults.normalizeModel3Renderer(null))
        assertEquals(RendererDefaults.OPENGL, RendererDefaults.normalizeModel3Renderer(RendererDefaults.OPENGL))
    }

    @Test
    fun explicitModel3VulkanChoiceIsPreserved() {
        assertEquals(RendererDefaults.VULKAN, RendererDefaults.normalizeModel3Renderer(RendererDefaults.VULKAN))
    }

    @Test
    fun unknownModel3RendererFallsBackToTheFastDefault() {
        assertEquals(RendererDefaults.OPENGL, RendererDefaults.normalizeModel3Renderer(RendererDefaults.SOFTWARE))
        assertEquals(RendererDefaults.OPENGL, RendererDefaults.normalizeModel3Renderer(RendererDefaults.AUTO))
    }
}
