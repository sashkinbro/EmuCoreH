package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class SegaTriggerRemappingTest {
    @Test fun triggerAxisCanDriveSupportedSegaButtonButHasNoUnsupportedFallback() {
        for (platform in listOf(EmulationPlatform.GENESIS, EmulationPlatform.MASTER_SYSTEM)) {
            GamepadManager.setPlatform(platform)
            try {
                val destination = if (platform == EmulationPlatform.GENESIS) "square" else "cross"
                assertEquals(destination, GamepadManager.resolveMappedActionIdForTriggerAxis("l2", mapOf(destination to 104)))
                assertNull(GamepadManager.resolveMappedActionIdForTriggerAxis("l2", emptyMap()))
                assertNull(GamepadManager.resolveMappedActionIdForTriggerAxis("r2", mapOf("coin" to 105)))
            } finally { GamepadManager.setPlatform(EmulationPlatform.DREAMCAST) }
        }
    }
    @Test fun dreamcastAnalogTriggerFallbackIsUnchanged() {
        GamepadManager.setPlatform(EmulationPlatform.DREAMCAST)
        assertEquals("l2", GamepadManager.resolveMappedActionIdForTriggerAxis("l2", emptyMap()))
        assertEquals("r2", GamepadManager.resolveMappedActionIdForTriggerAxis("r2", emptyMap()))
    }
}
