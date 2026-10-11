package com.sbro.emucoreh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomTouchControlGunTest {
    @Test
    fun gunControlsSurviveSanitizationAndGetAVisibleLabel() {
        for (action in listOf("gun_trigger", "gun_pedal", "gun_reload", "gun_recalibrate")) {
            val control = CustomTouchControl(id = action, actionId = action, label = "").sanitized()!!
            assertEquals(action, control.actionId)
            org.junit.Assert.assertTrue(control.label.isNotEmpty())
        }
    }
}
