package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class LightGunInputMathTest {
    @Test fun stickShapingRemovesDriftAndScalesRemainingTravel() {
        assertEquals(0f, shapeGamepadStickAxis(0.1f, 0.2f, 2f), 0.00001f)
        assertEquals(0.75f, shapeGamepadStickAxis(0.6f, 0.2f, 1.5f), 0.00001f)
        assertEquals(-1f, shapeGamepadStickAxis(-1f, 0.2f, 2f), 0.00001f)
    }
    @Test fun onlyGunPortConsumesTheSelectedStick() {
        val left = 0.4f to -0.2f
        val right = -0.3f to 0.8f
        assertNull(selectLightGunStick(setOf(1), 0, 1, left, right))
        assertEquals(left, selectLightGunStick(setOf(1), 1, 1, left, right))
        assertEquals(right, selectLightGunStick(setOf(1), 1, 2, left, right))
        assertNull(selectLightGunStick(setOf(1), 1, 0, left, right))
        assertNull(selectLightGunStick(setOf(1), 1, 3, left, right))
    }
    @Test fun gyroIntegrationCapsSensorGapAndIgnoresFirstTimestamp() {
        val aim = GyroLightGunAim()
        assertEquals(0.5f to 0.5f, aim.update(1f, -1f, 1_000_000_000L))
        val output = aim.update(1f, -1f, 3_000_000_000L)
        assertEquals(0.5525f, output.first, 0.00001f)
        assertEquals(0.4475f, output.second, 0.00001f)
    }
    @Test fun recenterDiscardsOldTimestampAndClampsAtScreenEdges() {
        val aim = GyroLightGunAim()
        for (i in 1..30) aim.update(1f, -1f, i * 50_000_000L)
        assertEquals(1f to 0f, aim.update(1f, -1f, 1_550_000_000L))
        aim.recenter()
        assertEquals(0.5f to 0.5f, aim.update(1f, -1f, 5_000_000_000L))
    }
    @Test fun dpadAimVectorNormalizesDiagonalsAndCancelsOpposites() {
        assertNull(dpadAimVector(up = false, down = false, left = false, right = false))
        assertNull(dpadAimVector(up = true, down = true, left = false, right = false))
        assertNull(dpadAimVector(up = false, down = false, left = true, right = true))
        assertEquals(1f to 0f, dpadAimVector(up = false, down = false, left = false, right = true))
        assertEquals(0f to -1f, dpadAimVector(up = true, down = false, left = false, right = false))
        assertEquals(0f to 1f, dpadAimVector(up = false, down = true, left = false, right = false))
        val upLeft = dpadAimVector(up = true, down = false, left = true, right = false)!!
        assertEquals(-0.70710678f, upLeft.first, 0.00001f)
        assertEquals(-0.70710678f, upLeft.second, 0.00001f)
        val downRight = dpadAimVector(up = false, down = true, left = false, right = true)!!
        assertEquals(0.70710678f, downRight.first, 0.00001f)
        assertEquals(0.70710678f, downRight.second, 0.00001f)
    }
}
