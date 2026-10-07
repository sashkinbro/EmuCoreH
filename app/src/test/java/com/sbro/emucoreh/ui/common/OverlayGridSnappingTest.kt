package com.sbro.emucoreh.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGridSnappingTest {
    private val step = 24f

    @Test
    fun `slow drags accumulate until the next grid cell`() {
        val residuals = mutableMapOf<String, Pair<Float, Float>>()
        var appliedTotal = 0f

        repeat(4) {
            val applied = snapOverlayDragDelta(
                residuals = residuals,
                controlId = "a",
                currentX = appliedTotal,
                currentY = 0f,
                delta = 3f to 0f,
                stepPx = step,
                enabled = true
            )
            appliedTotal += applied.first
        }

        assertEquals(step, appliedTotal, 0.01f)
    }

    @Test
    fun `continuing the drag keeps stepping cell by cell`() {
        val residuals = mutableMapOf<String, Pair<Float, Float>>()
        var appliedTotal = 0f

        repeat(12) {
            val applied = snapOverlayDragDelta(
                residuals = residuals,
                controlId = "a",
                currentX = appliedTotal,
                currentY = 0f,
                delta = 3f to 0f,
                stepPx = step,
                enabled = true
            )
            appliedTotal += applied.first
        }

        assertEquals(2f * step, appliedTotal, 0.01f)
    }

    @Test
    fun `a single fast drag moves the full distance`() {
        val residuals = mutableMapOf<String, Pair<Float, Float>>()
        val applied = snapOverlayDragDelta(
            residuals = residuals,
            controlId = "b",
            currentX = 0f,
            currentY = 0f,
            delta = 50f to -40f,
            stepPx = step,
            enabled = true
        )

        assertEquals(48f, applied.first, 0.01f)
        assertEquals(-48f, applied.second, 0.01f)
    }

    @Test
    fun `disabled snapping keeps the raw delta and no residual`() {
        val residuals = mutableMapOf<String, Pair<Float, Float>>()
        val applied = snapOverlayDragDelta(
            residuals = residuals,
            controlId = "a",
            currentX = 0f,
            currentY = 0f,
            delta = 3f to 2f,
            stepPx = step,
            enabled = false
        )

        assertEquals(3f, applied.first, 0.01f)
        assertEquals(2f, applied.second, 0.01f)
        assertTrue(residuals.isEmpty())
    }

    @Test
    fun `residual never grows beyond one cell`() {
        val residuals = mutableMapOf<String, Pair<Float, Float>>()

        repeat(20) {
            snapOverlayDragDelta(
                residuals = residuals,
                controlId = "a",
                currentX = 0f,
                currentY = 0f,
                delta = 5f to 5f,
                stepPx = step,
                enabled = true
            )
        }

        val residual = requireNotNull(residuals["a"])
        assertTrue(residual.first in -step..step)
        assertTrue(residual.second in -step..step)
    }
}
