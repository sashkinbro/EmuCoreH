package com.sbro.emucoreh.ui.emulation

import com.sbro.emucoreh.data.PerformanceOverlayMetrics
import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceOverlayLayoutTest {

    @Test
    fun versionHeaderIsShownOnlyWhenVersionMetricIsSelected() {
        val header = "EmuCoreH-0.0.4 | 119 | v2.0"
        val shown = buildPerformanceOverlayLayout("Speed:100%", PerformanceOverlayMetrics.VERSION, header)
        val hidden = buildPerformanceOverlayLayout("Speed:100%", PerformanceOverlayMetrics.SPEED, header)

        assertEquals(listOf("EmuCoreH-0.0.4|119|v2.0"), shown.mainLines)
        assertEquals(listOf("Speed:100%"), hidden.mainLines)
    }
}