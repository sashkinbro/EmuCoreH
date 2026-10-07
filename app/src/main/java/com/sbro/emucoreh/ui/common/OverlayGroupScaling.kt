package com.sbro.emucoreh.ui.common

import kotlin.math.roundToInt

/** One control inside a group that is scaled as a block in the layout editor. */
data class OverlayGroupScaleMember(
    val controlId: String,
    val baseX: Float,
    val baseY: Float,
    val offsetX: Float,
    val offsetY: Float,
    val scale: Int,
    val width: Float,
    val height: Float
)

data class OverlayGroupScaleResult(
    val controlId: String,
    val scale: Int,
    val offset: Pair<Float, Float>
)

/**
 * Scales every member of a control group around the group's visual centre. Positions and
 * sizes grow together so the relative layout of the whole block is preserved, and each
 * result stays inside the canvas.
 */
fun scaleOverlayControlGroup(
    members: List<OverlayGroupScaleMember>,
    factor: Float,
    minScale: Int,
    maxScale: Int,
    canvasWidth: Float,
    canvasHeight: Float
): List<OverlayGroupScaleResult> {
    if (members.isEmpty() || !factor.isFinite() || factor <= 0f) return emptyList()

    val left = members.minOf { it.baseX + it.offsetX }
    val top = members.minOf { it.baseY + it.offsetY }
    val right = members.maxOf { it.baseX + it.offsetX + it.width }
    val bottom = members.maxOf { it.baseY + it.offsetY + it.height }
    val centerX = (left + right) / 2f
    val centerY = (top + bottom) / 2f

    return members.map { member ->
        val currentScale = member.scale.coerceAtLeast(1)
        val nextScale = (currentScale * factor).roundToInt().coerceIn(minScale, maxScale)
        val actualFactor = nextScale.toFloat() / currentScale.toFloat()

        val currentLeft = member.baseX + member.offsetX
        val currentTop = member.baseY + member.offsetY
        val nextWidth = member.width * actualFactor
        val nextHeight = member.height * actualFactor

        val scaledLeft = centerX + (currentLeft - centerX) * actualFactor
        val scaledTop = centerY + (currentTop - centerY) * actualFactor
        val clampedLeft = scaledLeft.coerceIn(0f, (canvasWidth - nextWidth).coerceAtLeast(0f))
        val clampedTop = scaledTop.coerceIn(0f, (canvasHeight - nextHeight).coerceAtLeast(0f))

        OverlayGroupScaleResult(
            controlId = member.controlId,
            scale = nextScale,
            offset = (clampedLeft - member.baseX) to (clampedTop - member.baseY)
        )
    }
}
