package com.sbro.emucoreh.ui.common

import kotlin.math.roundToInt

/**
 * Snaps a drag to the grid while remembering the sub-cell remainder.
 *
 * Rounding every raw drag delta on its own silently discards movements smaller than
 * half a cell, so a slow drag never moves a control. Keeping the remainder and adding
 * it to the next delta lets slow drags accumulate until they cross a cell boundary.
 */
fun snapOverlayDragDelta(
    residuals: MutableMap<String, Pair<Float, Float>>,
    controlId: String,
    currentX: Float,
    currentY: Float,
    delta: Pair<Float, Float>,
    stepPx: Float,
    enabled: Boolean
): Pair<Float, Float> {
    if (!enabled || stepPx <= 0f) return delta
    val residual = residuals[controlId] ?: (0f to 0f)
    val totalX = delta.first + residual.first
    val totalY = delta.second + residual.second
    val snappedX = ((currentX + totalX) / stepPx).roundToInt() * stepPx
    val snappedY = ((currentY + totalY) / stepPx).roundToInt() * stepPx
    val appliedX = snappedX - currentX
    val appliedY = snappedY - currentY
    // Bounded so pushing against a clamped edge cannot build an endless offset.
    residuals[controlId] = (totalX - appliedX).coerceIn(-stepPx, stepPx) to
        (totalY - appliedY).coerceIn(-stepPx, stepPx)
    return appliedX to appliedY
}
