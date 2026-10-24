package com.sbro.emucoreh.ui.emulation

import com.sbro.emucoreh.data.PerformanceOverlayMetrics
import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceOverlayLayoutTest {

    @Test fun fixedResolutionCoresHideOnlyResolutionWithoutChangingStoredMetrics() {
        val metrics = PerformanceOverlayMetrics.FPS or PerformanceOverlayMetrics.RESOLUTION or PerformanceOverlayMetrics.AUDIO
        val text = "FPS:60\nRes:496x384\nAudio:OK"
        for (coreId in listOf(1, 2)) {
            val layout = buildPerformanceOverlayLayout(text, performanceOverlayMetricsForCore(metrics, coreId))
            assertEquals(listOf("FPS:60", "Audio:OK"), layout.mainLines)
            assertEquals(emptyList<String>(), layout.bottomLines)
        }
        assertEquals(metrics, performanceOverlayMetricsForCore(metrics, 0))
        assertEquals(listOf("Res:496x384"), buildPerformanceOverlayLayout(text, performanceOverlayMetricsForCore(metrics, 0)).bottomLines)
    }

    @Test
    fun versionHeaderIsShownOnlyWhenVersionMetricIsSelected() {
        val header = "EmuCoreH-0.0.4 | 119 | v2.0"
        val shown = buildPerformanceOverlayLayout("Speed:100%", PerformanceOverlayMetrics.VERSION, header)
        val hidden = buildPerformanceOverlayLayout("Speed:100%", PerformanceOverlayMetrics.SPEED, header)

        assertEquals(listOf("EmuCoreH-0.0.4|119|v2.0"), shown.mainLines)
        assertEquals(listOf("Speed:100%"), hidden.mainLines)
    }
}
