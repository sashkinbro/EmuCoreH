package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class HoldButtonTest {
    @Test fun tapDoesNotBecomeAHoldAfterRelease() {
        val button = HoldButton()
        val token = button.press()!!
        assertTrue(button.release())
        assertFalse(button.activate(token))
        assertFalse(button.active)
    }

    @Test fun holdDoesNotGenerateATapOnRelease() {
        val button = HoldButton()
        assertTrue(button.activate(button.press()!!))
        assertTrue(button.active)
        assertFalse(button.release())
        assertFalse(button.active)
        assertFalse(button.release())
    }

    @Test fun repeatedKeyDownDoesNotRestartTheHold() {
        val button = HoldButton()
        val token = button.press()!!
        assertNull(button.press())
        assertTrue(button.activate(token))
    }

    @Test fun resetCancelsPendingAndActiveHolds() {
        val button = HoldButton()
        val oldToken = button.press()!!
        button.reset()
        val newToken = button.press()!!
        assertFalse(button.activate(oldToken))
        assertTrue(button.activate(newToken))
        button.reset()
        assertFalse(button.active)
        assertFalse(button.release())
    }
}
