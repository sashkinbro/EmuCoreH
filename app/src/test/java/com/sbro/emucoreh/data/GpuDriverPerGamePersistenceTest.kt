package com.sbro.emucoreh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GpuDriverPerGamePersistenceTest {
    @Test fun selectionRespectsExplicitSystemAndGameOverride() {
        assertEquals("global", resolveGpuDriverSelection("global", null))
        assertEquals(null, resolveGpuDriverSelection("global", ""))
        assertEquals("game", resolveGpuDriverSelection("global", "game"))
    }

    @Test fun inheritedSystemAndCustomAreDistinct() {
        val profile = PerGameSettings(gameKey = "game", gameTitle = "Game")
        assertFalse(profile.toJson().has("gpuDriverOverride"))
        assertEquals(null, profile.toJson().toPerGameSettings().gpuDriverOverride)
        assertEquals("", profile.copy(gpuDriverOverride = "").toJson().toPerGameSettings().gpuDriverOverride)
        assertEquals("/driver/libvulkan.so", profile.copy(gpuDriverOverride = "/driver/libvulkan.so").toJson().toPerGameSettings().gpuDriverOverride)
    }
}
