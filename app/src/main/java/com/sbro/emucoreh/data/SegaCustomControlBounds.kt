package com.sbro.emucoreh.data

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class SegaCustomControlBounds(
    val x: Float, val y: Float, val width: Float, val height: Float,
    val minX: Float = 0f, val minY: Float = 0f, val travelX: Float = 0f, val travelY: Float = 0f
)

/** Fits both the unpressed control and its rotated/pressed visual footprint in the safe viewport. */
fun segaCustomControlBounds(
    viewportWidth: Float, viewportHeight: Float,
    requestedWidth: Float, requestedHeight: Float,
    positionX: Float, positionY: Float,
    maxVisualScale: Float = 1f, rotationDegrees: Float = 0f
): SegaCustomControlBounds {
    fun finitePositive(value: Float) = value.takeIf { it.isFinite() && it > 0f } ?: 0f
    val viewportW = finitePositive(viewportWidth)
    val viewportH = finitePositive(viewportHeight)
    var width = finitePositive(requestedWidth)
    var height = finitePositive(requestedHeight)
    if (viewportW == 0f || viewportH == 0f || width == 0f || height == 0f)
        return SegaCustomControlBounds(0f, 0f, 0f, 0f)
    val growth = maxVisualScale.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
    val angle = Math.toRadians((rotationDegrees.takeIf { it.isFinite() } ?: 0f).toDouble())
    val cosine = abs(cos(angle)).toFloat()
    val sine = abs(sin(angle)).toFloat()
    val visualWidth = maxOf(width, (width * cosine + height * sine) * growth)
    val visualHeight = maxOf(height, (width * sine + height * cosine) * growth)
    val shrink = minOf(1f, viewportW / visualWidth, viewportH / visualHeight)
    width *= shrink
    height *= shrink
    val marginX = ((visualWidth * shrink - width) / 2f).coerceAtLeast(0f)
    val marginY = ((visualHeight * shrink - height) / 2f).coerceAtLeast(0f)
    val travelX = (viewportW - width - 2f * marginX).coerceAtLeast(0f)
    val travelY = (viewportH - height - 2f * marginY).coerceAtLeast(0f)
    val normalizedX = positionX.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .5f
    val normalizedY = positionY.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .5f
    return SegaCustomControlBounds(marginX + travelX * normalizedX, marginY + travelY * normalizedY,
        width, height, marginX, marginY, travelX, travelY)
}
