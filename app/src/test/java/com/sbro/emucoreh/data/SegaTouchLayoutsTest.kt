package com.sbro.emucoreh.data

import com.sbro.emucoreh.core.EmulationPlatform
import org.junit.Assert.*
import org.junit.Test

class SegaTouchLayoutsTest {
    @Test fun hardwareSetsIncludeCorrectSystemButtonsAndNoAnalogControls() {
        EmulationPlatform.entries.filter { it.isSega }.forEach { platform ->
            val ids = SegaTouchLayouts.defaultProfile(platform).controlLayouts.keys
            assertTrue(ids.containsAll(listOf("dpad_up", "dpad_down", "dpad_left", "dpad_right", "a", "b")))
            assertFalse(ids.any { "stick" in it || it in setOf("l2", "r2", "coin", "service") })
            assertEquals(platform != EmulationPlatform.SG_1000, "start" in ids)
            assertEquals(platform in setOf(EmulationPlatform.GENESIS, EmulationPlatform.SEGA_CD, EmulationPlatform.SEGA_32X), "select" in ids)
        }
    }

    @Test fun labelsAndKeysFollowSegaWiringRatherThanDreamcastTriggers() {
        assertEquals("A", SegaTouchLayouts.label(EmulationPlatform.GENESIS, "x"))
        assertEquals("B", SegaTouchLayouts.label(EmulationPlatform.GENESIS, "a"))
        assertEquals("C", SegaTouchLayouts.label(EmulationPlatform.GENESIS, "b"))
        assertEquals(102, SegaTouchLayouts.keyCode(EmulationPlatform.GENESIS, "l1"))
        assertEquals(103, SegaTouchLayouts.keyCode(EmulationPlatform.GENESIS, "r1"))
        assertEquals("1", SegaTouchLayouts.label(EmulationPlatform.MASTER_SYSTEM, "a"))
        assertEquals("Pause", SegaTouchLayouts.label(EmulationPlatform.MASTER_SYSTEM, "start"))
        assertEquals("Start", SegaTouchLayouts.label(EmulationPlatform.GAME_GEAR, "start"))
        assertNull(SegaTouchLayouts.keyCode(EmulationPlatform.SG_1000, "start"))
        assertNull(SegaTouchLayouts.keyCode(EmulationPlatform.GAME_GEAR, "l1"))
        assertEquals(96, SegaTouchLayouts.keyCode(EmulationPlatform.GAME_GEAR, "cross"))
    }

    @Test fun defaultsAreIndependentAndDoNotChangeDreamcastDefaults() {
        val original = AppPreferences.defaultOverlayControlLayouts()
        val genesis = SegaTouchLayouts.defaultProfile(EmulationPlatform.GENESIS)
        val changed = genesis.copy(controlLayouts = genesis.controlLayouts + ("a" to OverlayControlLayout(scale = 180)))
        assertEquals(180, changed.controlLayouts.getValue("a").scale)
        assertEquals(100, SegaTouchLayouts.defaultProfile(EmulationPlatform.GENESIS).controlLayouts.getValue("a").scale)
        assertEquals(original, AppPreferences.defaultOverlayControlLayouts())
    }

    @Test fun profileCodecKeepsCustomGeometryAndPrunesUnsupportedHardwareActions() {
        val platform = EmulationPlatform.GAME_GEAR
        val layout = SegaTouchLayouts.defaultProfile(platform).let { profile ->
            profile.copy(controlLayouts = profile.controlLayouts + ("a" to OverlayControlLayout(
                offset = -125f to 79f, scale = 143, widthScale = 121, opacity = 56,
                visible = false, secondaryActionId = "start")))
        }
        val custom = CustomTouchControlLibrary(controls = listOf(
            CustomTouchControl(id = "one", actionId = "cross", secondaryActionId = "l1", positionX = 0.2f),
            CustomTouchControl(id = "unsupported", actionId = "coin")
        ))
        val decoded = SegaTouchLayoutPreferences.decode(platform,
            SegaTouchLayoutPreferences.encode(platform, SegaTouchLayoutProfile(layout, custom)))
        assertEquals(layout, decoded.layout)
        assertEquals(listOf("one"), decoded.customControls.controls.map { it.id })
        assertNull(decoded.customControls.controls.single().secondaryActionId)
        assertEquals(0.2f, decoded.customControls.controls.single().positionX)
    }
}
