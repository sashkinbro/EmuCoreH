package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class Model3TriggerPressureTest {
    @Test fun partialPedalsKeepAll256StepsAndReleaseImmediately() {
        val pedals = Model3TriggerPressure()
        for (n in 0..255) {
            assertFalse(Model3TriggerPressure.digitalPress(n, false))
            if (n > 0) assertFalse(Model3TriggerPressure.digitalPress(n, true))
            pedals.update(0, true, n, n > 0)
            assertEquals(n shl 8, pedals.packed())
        }
        pedals.update(0, false, 64, true)
        assertEquals((255 shl 8) or 64, pedals.packed())
        pedals.update(0, true, 0, false)
        assertEquals(64, pedals.packed())
        pedals.update(0, false, 0, false)
        assertEquals(0, pedals.packed())
    }

    @Test fun independentSourcesMergeWithoutReleasingAnotherPedal() {
        val pedals = Model3TriggerPressure()
        assertTrue(Model3TriggerPressure.digitalPress(0, true))
        pedals.update(0, true, 48, true)
        pedals.update(1, true, 192, true)
        pedals.update(2, false, 128, true)
        assertEquals(128 or (192 shl 8), pedals.packed())
        pedals.update(1, true, 0, false)
        assertEquals(128 or (48 shl 8), pedals.packed())
        pedals.reset() // Used by pause, boot/shutdown, disconnect and source changes.
        assertEquals(0, pedals.packed())
        pedals.update(2, false, 32, true)
        assertEquals(32, pedals.packed())
    }

    @Test fun digitalFallbackDoesNotLeaveAnalogPressureAndInvalidInputsAreBounded() {
        val pedals = Model3TriggerPressure()
        pedals.update(0, true, 200, true)
        pedals.update(0, true, 0, true) // Touch/key buttons use the existing digital path.
        assertEquals(0, pedals.packed())
        pedals.update(0, false, -20, true)
        pedals.update(0, true, 2000, true)
        assertEquals(255 shl 8, pedals.packed())
        pedals.update(100, true, 200, true)
        assertEquals(255 shl 8, pedals.packed())
    }

    @Test fun duplicateHardwareKeyEdgesCannotOverridePressureInEitherEventOrder() {
        for (keyFirst in listOf(false, true)) {
            val pedals = Model3TriggerPressure()
            fun key(pressed: Boolean) {
                if (!Model3TriggerPressure.suppressAxisKey(true, 105, 105, 105, true))
                    pedals.update(0, true, 0, pressed)
            }
            if (keyFirst) key(true)
            pedals.update(0, true, 64, true)
            if (!keyFirst) key(true)
            assertEquals(64 shl 8, pedals.packed())
            if (keyFirst) key(false)
            pedals.update(0, true, 0, false)
            if (!keyFirst) key(false)
            assertEquals(0, pedals.packed())
        }
        assertFalse(Model3TriggerPressure.suppressAxisKey(false, 105, 105, 105, true))
        assertFalse(Model3TriggerPressure.suppressAxisKey(true, 105, 105, 105, false))
        assertFalse(Model3TriggerPressure.suppressAxisKey(true, 96, 105, 105, true))
        assertFalse(Model3TriggerPressure.suppressAxisKey(true, 105, 96, 96, true))
        assertFalse(Model3TriggerPressure.suppressAxisKey(true, 105, 105, null, true))
        assertFalse(Model3TriggerPressure.suppressAxisKey(true, 105, 105, 104, true))
    }
}
