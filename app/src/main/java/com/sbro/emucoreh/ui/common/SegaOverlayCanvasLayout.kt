package com.sbro.emucoreh.ui.common

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sbro.emucoreh.R
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.data.AppPreferences
import com.sbro.emucoreh.data.OverlayControlLayout
import com.sbro.emucoreh.data.SegaTouchLayouts
import com.sbro.emucoreh.ui.emulation.segaTouchLayout

/** Editor and runtime share these slots, including saved per-control offsets and scale. */
internal fun buildSegaOverlayCanvasLayout(
    platform: EmulationPlatform, width: Dp, height: Dp, density: Density, scale: Float,
    controls: Map<String, OverlayControlLayout>, safeLeft: Dp, safeRight: Dp, safeTop: Dp, safeBottom: Dp,
    dpadOffset: Pair<Float, Float>, actionOffset: Pair<Float, Float>, centerOffset: Pair<Float, Float>, preview: Boolean
): OverlayCanvasLayout {
    val padding = 12.dp
    val left = safeLeft + padding
    val top = safeTop + padding
    val availableWidth = (width - left - safeRight - padding).coerceAtLeast(0.dp)
    val availableHeight = (height - top - safeBottom - padding).coerceAtLeast(0.dp)
    fun dp(px: Float): Dp = with(density) { px.toDp() }
    val keyIds = SegaTouchLayouts.controlIds(platform).associateBy { SegaTouchLayouts.keyCode(platform, it) }
    val buttons = segaTouchLayout(platform, availableWidth.value, availableHeight.value, scale).mapNotNull { bounds ->
        val id = keyIds[bounds.keyCode] ?: return@mapNotNull null
        val layout = controls[id] ?: OverlayControlLayout()
        val isDpad = id.startsWith("dpad_")
        val isCenter = id == "start" || id == "select"
        val group = when { isDpad -> dpadOffset; isCenter -> centerOffset; else -> actionOffset }
        val defaultGroup = when {
            isDpad -> AppPreferences.DEFAULT_DPAD_OFFSET_X to AppPreferences.DEFAULT_DPAD_OFFSET_Y
            isCenter -> AppPreferences.DEFAULT_CENTER_OFFSET_X to AppPreferences.DEFAULT_CENTER_OFFSET_Y
            else -> AppPreferences.DEFAULT_ACTION_OFFSET_X to AppPreferences.DEFAULT_ACTION_OFFSET_Y
        }
        val actualWidth = (bounds.width.dp * (layout.scale.coerceAtLeast(1) / 100f) *
            (layout.widthScale.coerceAtLeast(1) / 100f)).coerceAtMost(availableWidth)
        val actualHeight = (bounds.height.dp * (layout.scale.coerceAtLeast(1) / 100f)).coerceAtMost(availableHeight)
        val maxX = left + availableWidth - actualWidth
        val maxY = top + availableHeight - actualHeight
        fun finiteDp(value: Float): Dp = dp(value.takeIf { it.isFinite() } ?: 0f)
        val baseX = (left + bounds.x.dp + finiteDp(group.first - defaultGroup.first) -
            (actualWidth - bounds.width.dp) / 2f).coerceIn(left, maxX)
        val baseY = (top + bounds.y.dp + finiteDp(group.second - defaultGroup.second) -
            (actualHeight - bounds.height.dp) / 2f).coerceIn(top, maxY)
        val label = SegaTouchLayouts.label(platform, id)
        val drawable = when (label) {
            "A" -> R.drawable.ic_controller_a_button
            "B" -> R.drawable.ic_controller_b_button
            "X" -> R.drawable.ic_controller_x_button
            "Y" -> R.drawable.ic_controller_y_button
            "Z" -> R.drawable.ic_controller_y_button
            else -> requireNotNull(overlayDrawableForControl(id))
        }
        OverlayCanvasButtonSpec(id, drawable, actualWidth, actualHeight, baseX, baseY,
            (baseX + finiteDp(layout.offset.first)).coerceIn(left, maxX),
            (baseY + finiteDp(layout.offset.second)).coerceIn(top, maxY),
            if (isCenter) RoundedCornerShape(10.dp) else CircleShape,
            layout.opacity, layout.visible, label.takeUnless { it in setOf("A", "B", "X", "Y") })
    }.filter { preview || it.visible }
    return OverlayCanvasLayout(
        leftShoulders = emptyList(), rightShoulders = emptyList(),
        dpadButtons = buttons.filter { it.id.startsWith("dpad_") }, dpadCluster = null,
        actionButtons = buttons.filter { !it.id.startsWith("dpad_") && it.id !in setOf("start", "select") },
        centerButtons = buttons.filter { it.id in setOf("start", "select") }, leftStick = null, rightStick = null
    )
}
