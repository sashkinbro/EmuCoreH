package com.sbro.emucoreh.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlsEditorPositionRangeTest {
    @Test
    fun withoutInsetsTheRangeStaysNormalized() {
        val range = normalizedPositionRange(safeStartPx = 0f, safeEndPx = 0f, travelPx = 500f)

        assertEquals(0f, range.min, 0.0001f)
        assertEquals(1f, range.max, 0.0001f)
    }

    @Test
    fun cutoutInsetsExtendTheRangePastTheSafeArea() {
        val range = normalizedPositionRange(safeStartPx = 50f, safeEndPx = 20f, travelPx = 500f)

        assertEquals(-0.1f, range.min, 0.0001f)
        assertEquals(1.04f, range.max, 0.0001f)
    }

    @Test
    fun zeroTravelDoesNotDivideByZero() {
        val range = normalizedPositionRange(safeStartPx = 10f, safeEndPx = 10f, travelPx = 0f)

        assertEquals(-10f, range.min, 0.0001f)
        assertEquals(11f, range.max, 0.0001f)
    }
}
