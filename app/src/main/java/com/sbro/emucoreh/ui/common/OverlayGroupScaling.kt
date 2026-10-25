package com.sbro.emucoreh.ui.common

import kotlin.math.roundToInt
import kotlin.math.floor

/** One control inside a group that is scaled as a block in the layout editor. */
data class OverlayGroupScaleMember(
    val controlId: String,
    val baseX: Float,
    val baseY: Float,
    val offsetX: Float,
    val offsetY: Float,
    val scale: Int,
    val width: Float,
    val height: Float,
    val resizeCenterX: Float = baseX + width / 2f,
    val resizeCenterY: Float = baseY + height / 2f
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
    canvasHeight: Float,
    preserveSegaGeometry: Boolean = false,
    safeLeft: Float = 0f, safeTop: Float = 0f, safeRight: Float = 0f, safeBottom: Float = 0f
): List<OverlayGroupScaleResult> {
    if (members.isEmpty() || !factor.isFinite() || factor <= 0f) return emptyList()

    if (preserveSegaGeometry) {
        val left = members.minOf { it.baseX + it.offsetX }
        val top = members.minOf { it.baseY + it.offsetY }
        val right = members.maxOf { it.baseX + it.offsetX + it.width }
        val bottom = members.maxOf { it.baseY + it.offsetY + it.height }
        val availableWidth = (canvasWidth - safeLeft - safeRight).coerceAtLeast(1f)
        val availableHeight = (canvasHeight - safeTop - safeBottom).coerceAtLeast(1f)
        // One factor fits the complete block and respects every member's scale limit.
        val commonFactor = factor.coerceIn(
            members.maxOf { minScale.toFloat() / it.scale.coerceAtLeast(1) },
            members.minOf { maxScale.toFloat() / it.scale.coerceAtLeast(1) }
        ).coerceAtMost(minOf(availableWidth / (right - left).coerceAtLeast(1f), availableHeight / (bottom - top).coerceAtLeast(1f)))
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        val scaled = members.map { member ->
            val nextScale = floor(member.scale * commonFactor + .0001f).toInt().coerceIn(minScale, maxScale)
            val ratio = nextScale.toFloat() / member.scale.coerceAtLeast(1)
            val width = member.width * ratio
            val height = member.height * ratio
            val x = centerX + (member.baseX + member.offsetX - centerX) * ratio
            val y = centerY + (member.baseY + member.offsetY - centerY) * ratio
            // Sega slots resize around their centre, with their base kept in the safe area.
            val baseX = (member.resizeCenterX - width / 2f).coerceIn(safeLeft, (canvasWidth - safeRight - width).coerceAtLeast(safeLeft))
            val baseY = (member.resizeCenterY - height / 2f).coerceIn(safeTop, (canvasHeight - safeBottom - height).coerceAtLeast(safeTop))
            Triple(OverlayGroupScaleResult(member.controlId, nextScale, (x - baseX) to (y - baseY)), x to y, width to height)
        }
        val minX = scaled.minOf { it.second.first }
        val minY = scaled.minOf { it.second.second }
        val maxX = scaled.maxOf { it.second.first + it.third.first }
        val maxY = scaled.maxOf { it.second.second + it.third.second }
        val dx = 0f.coerceIn(safeLeft - minX, (canvasWidth - safeRight - maxX).coerceAtLeast(safeLeft - minX))
        val dy = 0f.coerceIn(safeTop - minY, (canvasHeight - safeBottom - maxY).coerceAtLeast(safeTop - minY))
        return scaled.map { (result, _, _) -> result.copy(offset = (result.offset.first + dx) to (result.offset.second + dy)) }
    }

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
