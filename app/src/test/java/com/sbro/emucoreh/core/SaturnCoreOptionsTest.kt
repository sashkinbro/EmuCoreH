package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class SaturnCoreOptionsTest {
    @Test fun beetleDefaultsAndOnlyActuallyLiveOptionsAreExposed() {
        val options = SaturnCoreOptions.options().associateBy { it.key }
        assertEquals("Auto Detect", options.getValue("beetle_saturn_cart").defaultValue)
        assertEquals("Auto Detect", options.getValue("beetle_saturn_region").defaultValue)
        assertEquals("english", options.getValue("beetle_saturn_autortc_lang").defaultValue)
        assertEquals("disabled", options.getValue("beetle_saturn_cdimagecache").defaultValue)
        assertEquals("disabled", options.getValue("beetle_saturn_midsync").defaultValue)
        assertEquals(listOf("beetle_saturn_midsync"), SaturnCoreOptions.options(liveOnly = true).map { it.key })
        assertTrue(SaturnCoreOptions.requiresRestart("beetle_saturn_cart"))
        assertTrue(SaturnCoreOptions.requiresRestart("beetle_saturn_region"))
        assertTrue(SaturnCoreOptions.requiresRestart("beetle_saturn_autortc_lang"))
        assertTrue(SaturnCoreOptions.requiresRestart("beetle_saturn_cdimagecache"))
        assertFalse(SaturnCoreOptions.requiresRestart("beetle_saturn_midsync"))
        assertTrue(SaturnCoreOptions.options(EmulationPlatform.GENESIS).isEmpty())
        assertFalse(options.containsKey("yabasanshiro_force_hle_bios"))
        assertFalse(options.containsKey("yabasanshiro_frameskip"))
        for (option in options.values) assertTrue(option.choices.any { it.value == option.defaultValue })
    }
}
