package com.sbro.emucoreh.core

import com.sbro.emucoreh.data.SegaTouchLayouts
import org.junit.Assert.*
import org.junit.Test

class Model3PlatformControlsTest {
    @Test fun `Model 3 uses isolated controls without becoming a jgenesis platform`() {
        assertTrue(EmulationPlatform.MODEL_3.usesPlatformTouchLayouts)
        assertFalse(EmulationPlatform.MODEL_3.isSega)
        assertEquals(2, EmulationPlatform.MODEL_3.coreId)
    }

    @Test fun `Model 3 has all six actions coin start and cabinet buttons`() {
        val platform = EmulationPlatform.MODEL_3
        assertEquals(setOf("dpad_up", "dpad_down", "dpad_left", "dpad_right", "a", "b", "x", "y",
            "l1", "r1", "l2", "r2", "select", "start", "l3", "r3"), SegaTouchLayouts.controlIds(platform))
        assertEquals(109, SegaTouchLayouts.keyCode(platform, "select"))
        assertEquals(106, SegaTouchLayouts.keyCode(platform, "l3"))
        assertEquals(107, SegaTouchLayouts.keyCode(platform, "r3"))
        assertEquals("A", SegaTouchLayouts.label(platform, "a"))
        assertEquals("L", SegaTouchLayouts.label(platform, "l1"))
        assertEquals(106, SegaControlProfiles.defaults(platform)["l3"])
        assertEquals(104, SegaControlProfiles.defaults(platform)["l2"])
        assertEquals(105, SegaControlProfiles.defaults(platform)["r2"])
    }
}
