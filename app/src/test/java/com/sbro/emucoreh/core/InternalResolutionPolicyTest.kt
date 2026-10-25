package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class InternalResolutionPolicyTest {
    @Test fun model3RoutesBoundedIntegerScaleToSupermodelOption() {
        assertEquals("supermodel_internal_resolution" to "1x", internalResolutionCoreOption(EmulationPlatform.MODEL_3, 1f))
        assertEquals("supermodel_internal_resolution" to "3x", internalResolutionCoreOption(EmulationPlatform.MODEL_3, 2.6f))
        assertEquals("supermodel_internal_resolution" to "4x", internalResolutionCoreOption(EmulationPlatform.MODEL_3, 20f))
        assertEquals("supermodel_internal_resolution" to "1x", internalResolutionCoreOption(EmulationPlatform.MODEL_3, -2f))
    }

    @Test fun switchingCoreKeepsFlycastRangeAndDoesNotRouteSegaResolution() {
        assertEquals(4, EmulationPlatform.MODEL_3.maxInternalResolutionMultiplier)
        assertEquals(20, EmulationPlatform.DREAMCAST.maxInternalResolutionMultiplier)
        assertEquals("reicast_internal_resolution" to "12800x9600", internalResolutionCoreOption(EmulationPlatform.DREAMCAST, 20f))
        for (platform in EmulationPlatform.entries.filter { it.isSega }) {
            assertFalse(platform.supportsInternalResolution)
            assertNull(internalResolutionCoreOption(platform, 4f))
        }
    }

    @Test fun model3SelectorAndInheritedResetShowEffectiveBoundedValue() {
        val platform = EmulationPlatform.MODEL_3
        assertTrue(platform.supportsInternalResolution)
        assertEquals(listOf(100, 200, 300, 400), buildUpscaleOptions("Native", platform.maxInternalResolutionMultiplier).map { it.first })
        assertEquals(400, upscaleMultiplierKey(normalizeUpscaleForPlatform(20f, platform)))
        assertEquals(1f, normalizeUpscaleForPlatform(UPSCALE_DEFAULT, platform))
        assertEquals(20f, normalizeUpscaleForPlatform(20f, EmulationPlatform.DREAMCAST))
    }
}
