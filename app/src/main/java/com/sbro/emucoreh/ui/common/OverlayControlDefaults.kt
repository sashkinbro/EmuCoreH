package com.sbro.emucoreh.ui.common

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

val OverlayClusterGapLandscape = 32.dp
val OverlayClusterGapPortrait = 34.dp
val OverlayActionGapLandscape = 48.dp
val OverlayActionGapPortrait = 52.dp
val OverlayPrimaryControlGapLandscape = 24.dp
val OverlayPrimaryControlGapPortrait = 16.dp
val OverlayCenterInlineGapLandscape = 10.dp
val OverlayCenterInlineGapPortrait = 12.dp
val OverlayCenterSelectOpticalNudgeX = (-2).dp
val OverlayCenterToggleOpticalNudgeY = 0.dp
val OverlayCenterStartOpticalNudgeX = 2.dp

fun overlayInlineGroupOffset(
    widths: List<Dp>,
    gap: Dp,
    index: Int
): Dp {
    val totalWidth = widths.fold(0.dp) { acc, width -> acc + width } +
        gap * (widths.size - 1).coerceAtLeast(0)
    val precedingWidth = widths.take(index).fold(0.dp) { acc, width -> acc + width } +
        gap * index.coerceAtLeast(0)
    return -(totalWidth / 2f) + precedingWidth
}

fun overlayClusterStep(buttonSize: Dp, gap: Dp): Dp = buttonSize + gap

// Default slot of a single D-pad arrow inside its block, relative to the block's top-left.
fun overlayDpadArrowDefault(direction: OverlayDpadDirection, clusterSize: Dp): DpOffset {
    val arrowSize = clusterSize * OverlayDpadClusterArrowScale
    val inset = (clusterSize - arrowSize) / 2f
    return when (direction) {
        OverlayDpadDirection.Up -> DpOffset(inset, 0.dp)
        OverlayDpadDirection.Down -> DpOffset(inset, clusterSize - arrowSize)
        OverlayDpadDirection.Left -> DpOffset(0.dp, inset)
        OverlayDpadDirection.Right -> DpOffset(clusterSize - arrowSize, inset)
    }
}

// Touch/selection surface of a D-pad block. Arrows can be nudged outside the base
// square by the layout editor, so the surface grows to keep covering every arrow and
// the block stays fully usable instead of clipping moved buttons.
data class OverlayDpadClusterSurface(
    val offset: DpOffset,
    val width: Dp,
    val height: Dp
)

fun overlayDpadClusterSurface(
    size: Dp,
    directionOffsets: Map<OverlayDpadDirection, DpOffset>
): OverlayDpadClusterSurface {
    val arrowSize = size * OverlayDpadClusterArrowScale
    var left = 0.dp
    var top = 0.dp
    var right = size
    var bottom = size
    OverlayDpadDirection.entries.forEach { direction ->
        val anchor = overlayDpadArrowDefault(direction, size) +
            (directionOffsets[direction] ?: DpOffset.Zero)
        left = minOf(left, anchor.x)
        top = minOf(top, anchor.y)
        right = maxOf(right, anchor.x + arrowSize)
        bottom = maxOf(bottom, anchor.y + arrowSize)
    }
    return OverlayDpadClusterSurface(
        offset = DpOffset(left, top),
        width = right - left,
        height = bottom - top
    )
}
