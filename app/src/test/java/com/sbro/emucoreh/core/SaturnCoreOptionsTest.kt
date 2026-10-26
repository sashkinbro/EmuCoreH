package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class SaturnCoreOptionsTest {
    @Test fun conservativeDefaultsAndOnlyActuallyLiveOptionsAreExposed() {
        val options = SaturnCoreOptions.options().associateBy { it.key }
        assertEquals("disabled", options.getValue("yabasanshiro_frameskip").defaultValue)
        assertEquals("dynarec", options.getValue("yabasanshiro_sh2coretype").defaultValue)
        assertEquals("original", options.getValue("yabasanshiro_resolution_mode").defaultValue)
        assertEquals(setOf("original", "2x", "4x"), options.getValue("yabasanshiro_resolution_mode").choices.map { it.value }.toSet())
        assertEquals(listOf("yabasanshiro_frameskip"), SaturnCoreOptions.options(liveOnly = true).map { it.key })
        assertTrue(SaturnCoreOptions.requiresRestart("yabasanshiro_sh2coretype"))
        assertFalse(SaturnCoreOptions.requiresRestart("yabasanshiro_frameskip"))
        assertTrue(SaturnCoreOptions.options(EmulationPlatform.GENESIS).isEmpty())
        assertFalse(options.containsKey("yabasanshiro_force_hle_bios"))
        for (option in options.values) assertTrue(option.choices.any { it.value == option.defaultValue })
    }
}
