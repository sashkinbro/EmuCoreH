package com.sbro.emucoreh.ui.common

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.data.AppPreferences
import com.sbro.emucoreh.data.OverlayControlLayout
import com.sbro.emucoreh.data.SegaTouchLayouts

/** Shared by gameplay and the editor. Custom offsets are applied after default slots fit. */
internal fun buildModel3OverlayCanvasLayout(
    width: Dp, height: Dp, density: Density, scale: Float, stickScale: Float,
    controls: Map<String, OverlayControlLayout>, safeLeft: Dp, safeRight: Dp,
    safeTop: Dp, safeBottom: Dp, dpadOffset: Pair<Float, Float>,
    leftStickOffset: Pair<Float, Float>, rightStickOffset: Pair<Float, Float>,
    actionOffset: Pair<Float, Float>, centerOffset: Pair<Float, Float>, preview: Boolean
): OverlayCanvasLayout {
    val platform = EmulationPlatform.MODEL_3
    val ids = SegaTouchLayouts.controlIds(platform)
    val systemIds = setOf("select", "start", "l3", "r3")
    fun control(id: String) = controls[id] ?: OverlayControlLayout()
    fun finite(value: Float, fallback: Float = 0f) = value.takeIf(Float::isFinite) ?: fallback
    fun pixels(value: Float) = with(density) { finite(value).toDp() }.value
    fun buttonScale(id: String) = control(id).scale.coerceAtLeast(1) / 100f
    fun widthScale(id: String) = control(id).widthScale.coerceAtLeast(1) / 100f
    val left = safeLeft.value + 12f
    val top = safeTop.value + 12f
    val w = (width.value - left - safeRight.value - 12f).coerceAtLeast(0f)
    val h = (height.value - top - safeBottom.value - 12f).coerceAtLeast(0f)
    val requested = finite(scale, 1f).coerceIn(.5f, 1.5f)
    val button = 48f * requested
    // Slots account for saved sizes too. Position changes do not rearrange other controls.
    val largestButton = ids.maxOf { buttonScale(it) * maxOf(1f, widthScale(it)) }
    val diameter = button * largestButton
    val analog = 120f * requested * finite(stickScale, 1f).coerceIn(.5f, 2f)
    val leftSize = analog * buttonScale("left_stick")
    val rightSize = analog * buttonScale("right_stick")
    val leftPanel = leftSize * maxOf(1f, widthScale("left_stick"))
    val rightPanel = rightSize * maxOf(1f, widthScale("right_stick"))
    val stickHeight = maxOf(leftSize, rightSize)
    fun fit(gap: Float) = minOf(1f,
        ((w - 6f * gap) / (7f * diameter)).coerceAtLeast(0f),
        ((w - gap) / (leftPanel + rightPanel)).coerceAtLeast(0f),
        ((h - 4f * gap) / (stickHeight + 3.8f * diameter)).coerceAtLeast(0f))
    val responsive = fit(4f)
    val gap = maxOf(4f, 8f * responsive)
    val factor = fit(gap)
    val d = diameter * factor
    val step = d + gap
    val isLandscape = w >= h
    val dpadScale = requested * factor
    val dpad = overlayDpadCrossGeometry((if (isLandscape) 130f else 150f) * dpadScale, isLandscape)
    val systemHeight = .8f * d
    val systemTop = top + h - systemHeight
    val buttonBottom = systemTop - gap
    val crossTop = buttonBottom - dpad.extent
    val faceLeft = left + w - (4f * d + 3f * gap)
    val rightStickSize = (rightSize * factor).coerceAtMost(h)
    val rightStickBottom = maxOf(top, crossTop - gap - rightStickSize) + rightStickSize
    val faceTop = maxOf(buttonBottom - (2f * d + gap) - 10f * requested * factor,
        rightStickBottom + gap)
    val slots = linkedMapOf(
        "dpad_up" to (left + dpad.centerOffset to crossTop),
        "dpad_left" to (left to crossTop + dpad.centerOffset),
        "dpad_right" to (left + dpad.step to crossTop + dpad.centerOffset),
        "dpad_down" to (left + dpad.centerOffset to crossTop + dpad.step)
    )
    listOf("l1", "y", "r1", "l2", "x", "a", "b", "r2").forEachIndexed { index, id ->
        slots[id] = faceLeft + index % 4 * step to faceTop + index / 4 * step
    }
    val systemCenter = (left + dpad.extent + faceLeft) / 2f
    val availableSystemWidth = 2f * minOf(systemCenter - left, left + w - systemCenter)
    val systemWidth = ((minOf(7f * d + 3f * gap, availableSystemWidth) - 3f * gap) / 4f).coerceAtLeast(0f)
    val systemRowWidth = 4f * systemWidth + 3f * gap
    val systemLeft = (systemCenter - systemRowWidth / 2f)
        .coerceIn(left, (left + w - systemRowWidth).coerceAtLeast(left))
    systemIds.forEachIndexed { index, id -> slots[id] = systemLeft + index * (systemWidth + gap) to systemTop }
    fun adjustment(offset: Pair<Float, Float>, defaultX: Float, defaultY: Float) =
        pixels(offset.first - defaultX) to pixels(offset.second - defaultY)
    val buttons = slots.map { (id, slot) ->
        val layout = control(id)
        val system = id in systemIds
        val isDpad = id.startsWith("dpad_")
        val baseButton = if (isDpad) dpad.tile / factor else button
        val buttonWidth = (if (system) systemWidth * buttonScale(id) * widthScale(id) / largestButton
            else baseButton * factor * buttonScale(id) * widthScale(id)).coerceAtMost(w)
        val buttonHeight = (baseButton * factor * buttonScale(id) * if (system) .8f else 1f).coerceAtMost(h)
        val group = when {
            system -> adjustment(centerOffset, AppPreferences.DEFAULT_CENTER_OFFSET_X, AppPreferences.DEFAULT_CENTER_OFFSET_Y)
            isDpad -> adjustment(dpadOffset, AppPreferences.DEFAULT_DPAD_OFFSET_X, AppPreferences.DEFAULT_DPAD_OFFSET_Y)
            else -> adjustment(actionOffset, AppPreferences.DEFAULT_ACTION_OFFSET_X, AppPreferences.DEFAULT_ACTION_OFFSET_Y)
        }
        val slotWidth = when {
            system -> systemWidth
            isDpad -> dpad.tile
            else -> d
        }
        val slotHeight = when {
            system -> systemHeight
            isDpad -> dpad.tile
            else -> d
        }
        val baseX = (slot.first + (slotWidth - buttonWidth) / 2f + group.first).coerceIn(left, left + w - buttonWidth)
        val baseY = (slot.second + (slotHeight - buttonHeight) / 2f + group.second).coerceIn(top, top + h - buttonHeight)
        val label = SegaTouchLayouts.label(platform, id)
        val shape = when {
            system -> RoundedCornerShape(10.dp)
            isDpad -> RoundedCornerShape(8.dp)
            else -> CircleShape
        }
        OverlayCanvasButtonSpec(id, requireNotNull(overlayDrawableForControl(id)), buttonWidth.dp, buttonHeight.dp,
            baseX.dp, baseY.dp,
            (baseX + pixels(layout.offset.first)).coerceIn(left, left + w - buttonWidth).dp,
            (baseY + pixels(layout.offset.second)).coerceIn(top, top + h - buttonHeight).dp,
            shape, layout.opacity, layout.visible,
            label.takeUnless { it in setOf("A", "B", "X", "Y") })
    }.filter { preview || it.visible }
    fun stick(id: String, size: Float, panel: Float, onRight: Boolean, offset: Pair<Float, Float>, dx: Float, dy: Float): OverlayCanvasStickSpec {
        val layout = control(id)
        val actualSize = (size * factor).coerceAtMost(h)
        val panelWidth = (panel * factor).coerceAtMost(w)
        val group = adjustment(offset, dx, dy)
        val minX = left + (panelWidth - actualSize) / 2f
        val maxX = left + w - panelWidth + (panelWidth - actualSize) / 2f
        val baseX = ((if (onRight) maxX else minX) + group.first).coerceIn(minX, maxX)
        val baseY = (crossTop - gap - actualSize + group.second).coerceIn(top, top + h - actualSize)
        return OverlayCanvasStickSpec(id, actualSize.dp, layout.widthScale, baseX.dp, baseY.dp,
            (baseX + pixels(layout.offset.first)).coerceIn(minX, maxX).dp,
            (baseY + pixels(layout.offset.second)).coerceIn(top, top + h - actualSize).dp,
            layout.opacity, layout.visible)
    }
    return OverlayCanvasLayout(emptyList(), emptyList(), buttons.filter { it.id.startsWith("dpad_") }, null,
        buttons.filter { !it.id.startsWith("dpad_") && it.id !in systemIds }, buttons.filter { it.id in systemIds },
        stick("left_stick", leftSize, leftPanel, false, leftStickOffset, AppPreferences.DEFAULT_LSTICK_OFFSET_X, AppPreferences.DEFAULT_LSTICK_OFFSET_Y).takeIf { preview || it.visible },
        stick("right_stick", rightSize, rightPanel, true, rightStickOffset, AppPreferences.DEFAULT_RSTICK_OFFSET_X, AppPreferences.DEFAULT_RSTICK_OFFSET_Y).takeIf { preview || it.visible })
}
