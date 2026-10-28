package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class FlycastOptionCoverageTest {
    @Test fun gameMenusIncludeTheSameCoreOptionsAsSettingsWithoutDuplicateResolution() {
        val emulation = FlycastCoreOptions.gameMenuEmulationOptions().map { it.key }.toSet()
        val expected = FlycastCoreOptions.systemOptions() + FlycastCoreOptions.performanceOptions() +
            FlycastCoreOptions.hacksOptions() + FlycastCoreOptions.vmuOptions() + FlycastCoreOptions.networkOptions()
        assertEquals(expected.map { it.key }.toSet(), emulation)
        val controls = FlycastCoreOptions.gameMenuControlsOptions().map { it.key }.toSet()
        assertEquals((FlycastCoreOptions.inputOptions() + FlycastCoreOptions.expansionsOptions()).map { it.key }.toSet(), controls)
        assertFalse(FlycastCoreOptions.gameMenuGraphicsOptions().any { it.key == FlycastCoreOptions.INTERNAL_RESOLUTION_KEY })
    }

    @Test fun gameOverridesWinBeforeCoreBootAndInvalidValuesAreIgnored() {
        val result = FlycastCoreOptions.startupOptions(
            mapOf("reicast_region" to "USA", "reicast_language" to "English"),
            mapOf("reicast_region" to "Japan", "reicast_language" to "not-a-language", "unknown_key" to "x")
        )
        assertEquals("Japan", result["reicast_region"])
        assertEquals("English", result["reicast_language"])
        assertFalse(result.containsKey("unknown_key"))
        assertTrue(FlycastCoreOptions.supportsGameOverride("reicast_custom_textures"))
        assertFalse(FlycastCoreOptions.supportsGameOverride(FlycastCoreOptions.INTERNAL_RESOLUTION_KEY))
    }
}
