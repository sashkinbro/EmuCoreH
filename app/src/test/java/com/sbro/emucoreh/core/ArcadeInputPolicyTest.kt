package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class ArcadeInputPolicyTest {
    @Test fun automaticDeviceUsesGameDetectionAndExplicitControllerWins() {
        assertFalse(ArcadeInputPolicy.isLightGun(0, -1))
        assertFalse(ArcadeInputPolicy.isLightGun(0, 1))
        assertTrue(ArcadeInputPolicy.isLightGun(0, 2))
        assertFalse(ArcadeInputPolicy.isLightGun(1, 2))
        assertTrue(ArcadeInputPolicy.isLightGun(2, 0))
    }

    @Test fun aimingUsesImageRectangleAndGuttersAreOffscreen() {
        val rect = floatArrayOf(200f, 40f, 1000f, 640f)
        assertEquals(AimPoint(.5f, .5f, false), LightGunAimGeometry.fromWindow(600f, 340f, rect))
        assertEquals(AimPoint(0f, .5f, true), LightGunAimGeometry.fromWindow(100f, 340f, rect))
        assertEquals(AimPoint(1f, 1f, false), LightGunAimGeometry.fromWindow(1000f, 640f, rect))
    }

    @Test fun invalidGeometryDoesNotShootAtArbitraryCoordinates() {
        assertTrue(LightGunAimGeometry.fromWindow(10f, 10f, floatArrayOf(0f, 0f, 0f, 0f)).offscreen)
        assertTrue(LightGunAimGeometry.fromWindow(Float.NaN, 0f, floatArrayOf(0f, 0f, 100f, 100f)).offscreen)
    }

    @Test fun tapBetweenFramesReachesCoreAndResetDiscardsIt() {
        val input = LightGunInputState()
        input.setButton(0, true)
        input.setButton(0, false)
        assertEquals(1, input.nextFrame().buttons)
        assertEquals(1, input.nextFrame().buttons)
        assertEquals(1, input.nextFrame().buttons)
        assertEquals(0, input.nextFrame().buttons)
        input.setButton(2, true)
        input.reset()
        assertEquals(0, input.nextFrame().buttons)
    }

    @Test fun portsKeepAimAndButtonStateIndependent() {
        val p1 = LightGunInputState()
        val p2 = LightGunInputState()
        p1.setAim(.1f, .9f, true)
        p2.setButton(1, true)
        assertEquals(AimPoint(.1f, .9f, true), p1.nextFrame().aim)
        assertEquals(0, p1.nextFrame().buttons)
        assertEquals(AimPoint(.5f, .5f, false), p2.nextFrame().aim)
        assertEquals(2, p2.nextFrame().buttons)
    }

    @Test fun cabinetTapsAreLatchedAndPauseCleanupKeepsAim() {
        val input = LightGunInputState()
        input.setAim(.25f, .75f, false)
        input.setButton(4, true)
        input.setButton(4, false)
        assertEquals(16, input.nextFrame().buttons)
        input.reset(recenter = false)
        assertEquals(LightGunFrame(AimPoint(.25f, .75f, false), 0), input.nextFrame())
    }
    @Test fun persistedDreamcastGunSelectionDoesNotEnableOtherCoresGunInput() {
        for (platform in EmulationPlatform.entries.filter { it.coreId != 0 }) {
            assertFalse(ArcadeInputPolicy.isLightGun(2, -1, platform))
            assertFalse(ArcadeInputPolicy.isLightGun(2, 2, platform))
            assertFalse(ArcadeInputPolicy.isLightGun(0, 2, platform))
        }
        assertTrue(ArcadeInputPolicy.isLightGun(2, -1, EmulationPlatform.DREAMCAST))
        assertTrue(ArcadeInputPolicy.isLightGun(0, 2, EmulationPlatform.DREAMCAST))
    }

    @Test fun reusedFlycastGunRequestSelectsJoypadForModel3Session() {
        val persistedGunRequest = 2
        assertEquals(4, ArcadeInputPolicy.controllerDevice(persistedGunRequest, 2, EmulationPlatform.DREAMCAST))
        for (platform in EmulationPlatform.entries.filter { it.coreId != 0 }) {
            for (mode in listOf(-1, 0, 1, 2)) {
                assertEquals("Joypad must be selected for $platform", 1,
                    ArcadeInputPolicy.controllerDevice(persistedGunRequest, mode, platform))
            }
        }
        assertEquals(1, ArcadeInputPolicy.controllerDevice(1, 2, EmulationPlatform.DREAMCAST))
        assertEquals(4, ArcadeInputPolicy.controllerDevice(0, 2, EmulationPlatform.DREAMCAST))
    }
}
