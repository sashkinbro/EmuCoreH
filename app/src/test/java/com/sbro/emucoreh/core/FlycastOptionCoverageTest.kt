package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class FlycastOptionCoverageTest {
    @Test fun legacyHlePreferencesCannotRestoreBioslessBoot() {
        val key = FlycastCoreOptions.HLE_BIOS_KEY
        assertFalse(FlycastCoreOptions.systemOptions().any { it.key == key })
        assertFalse(FlycastCoreOptions.gameMenuEmulationOptions().any { it.key == key })
        assertFalse(FlycastCoreOptions.supportsGameOverride(key))
        assertTrue(FlycastCoreOptions.isManagedKey(key))
        assertFalse(FlycastCoreOptions.startupOptions(mapOf(key to "enabled"),
            mapOf(key to "enabled")).containsKey(key))
    }
    @Test fun consecutiveGameLaunchesRestoreUnconfiguredOptionsWithoutShadowingManagedSettings() {
        val native = mutableMapOf<String, String>()
        native.putAll(FlycastCoreOptions.startupOptions(emptyMap(), mapOf(
            "reicast_frame_skipping" to "3", "reicast_region" to "Japan")))
        assertEquals("3", native["reicast_frame_skipping"])
        native.putAll(FlycastCoreOptions.startupOptions(emptyMap(), emptyMap()))
        assertEquals(FlycastCoreOptions.option("reicast_frame_skipping")!!.defaultValue,
            native["reicast_frame_skipping"])
        assertEquals(FlycastCoreOptions.option("reicast_region")!!.defaultValue, native["reicast_region"])
        for (key in listOf(FlycastCoreOptions.INTERNAL_RESOLUTION_KEY, FlycastCoreOptions.HLE_BIOS_KEY,
            "reicast_custom_textures", "reicast_preload_custom_textures")) {
            assertFalse("Managed $key must keep its separate runtime path", native.containsKey(key))
        }
    }

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
