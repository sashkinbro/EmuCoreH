package com.sbro.emucoreh.ui.common

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.data.AppPreferences
import com.sbro.emucoreh.data.SegaTouchLayouts
import org.junit.Assert.assertEquals
import org.junit.Test

class RightActionDefaultLayoutTest {
    @Test fun segaPlatformsMatchDreamcastLeftAndRightDefaultHeights() {
        for ((width, height) in listOf(800f to 450f, 800f to 360f, 360f to 800f))
            for (scale in listOf(.8f, 1f, 1.2f)) for (hasInsets in listOf(false, true)) {
            fun layout(platform: EmulationPlatform): OverlayCanvasLayout {
                val controls = if (platform.usesPlatformTouchLayouts) SegaTouchLayouts.defaultProfile(platform).controlLayouts
                    else AppPreferences.defaultOverlayControlLayouts()
                return buildOverlayCanvasLayout(
                    width.dp, height.dp, Density(3f), scale, 1f,
                    AppPreferences.DEFAULT_DPAD_OFFSET_X to AppPreferences.DEFAULT_DPAD_OFFSET_Y,
                    AppPreferences.DEFAULT_LSTICK_OFFSET_X to AppPreferences.DEFAULT_LSTICK_OFFSET_Y,
                    AppPreferences.DEFAULT_RSTICK_OFFSET_X to AppPreferences.DEFAULT_RSTICK_OFFSET_Y,
                    AppPreferences.DEFAULT_ACTION_OFFSET_X to AppPreferences.DEFAULT_ACTION_OFFSET_Y,
                    AppPreferences.DEFAULT_LBTN_OFFSET_X to AppPreferences.DEFAULT_LBTN_OFFSET_Y,
                    AppPreferences.DEFAULT_RBTN_OFFSET_X to AppPreferences.DEFAULT_RBTN_OFFSET_Y,
                    AppPreferences.DEFAULT_CENTER_OFFSET_X to AppPreferences.DEFAULT_CENTER_OFFSET_Y,
                    controls, (if (hasInsets) 15 else 0).dp, (if (hasInsets) 27 else 0).dp,
                    (if (hasInsets) 8 else 0).dp, (if (hasInsets) 20 else 0).dp,
                    previewMode = true, platform = platform
                )
            }
            fun center(buttons: List<OverlayCanvasButtonSpec>): Float =
                (buttons.minOf { it.y.value } + buttons.maxOf { (it.y + it.height).value }) / 2f
            val dreamcast = layout(EmulationPlatform.DREAMCAST)
            val reference = center(dreamcast.actionButtons)
            assertEquals("Dreamcast left/right", reference, center(dreamcast.dpadButtons), .01f)
            for (platform in EmulationPlatform.entries.filter { it.usesPlatformTouchLayouts }) {
                val actual = layout(platform)
                val faces = if (platform.isSaturn) actual.actionButtons.filter { it.id !in setOf("l2", "r2") }
                    else actual.actionButtons
                assertEquals("$platform ${width}x$height scale=$scale left", reference, center(actual.dpadButtons), .01f)
                assertEquals("$platform ${width}x$height scale=$scale right", reference, center(faces), .01f)
            }
        }
    }
    @Test fun rightActionButtonsHaveComfortableBottomClearance() {
        val positions = EmulationPlatform.entries.associateWith { platform ->
            val controls = if (platform.usesPlatformTouchLayouts) SegaTouchLayouts.defaultProfile(platform).controlLayouts
                else AppPreferences.defaultOverlayControlLayouts()
            val layout = buildOverlayCanvasLayout(
                800.dp, 450.dp, Density(3f), 1f, 1f,
                AppPreferences.DEFAULT_DPAD_OFFSET_X to AppPreferences.DEFAULT_DPAD_OFFSET_Y,
                AppPreferences.DEFAULT_LSTICK_OFFSET_X to AppPreferences.DEFAULT_LSTICK_OFFSET_Y,
                AppPreferences.DEFAULT_RSTICK_OFFSET_X to AppPreferences.DEFAULT_RSTICK_OFFSET_Y,
                AppPreferences.DEFAULT_ACTION_OFFSET_X to AppPreferences.DEFAULT_ACTION_OFFSET_Y,
                AppPreferences.DEFAULT_LBTN_OFFSET_X to AppPreferences.DEFAULT_LBTN_OFFSET_Y,
                AppPreferences.DEFAULT_RBTN_OFFSET_X to AppPreferences.DEFAULT_RBTN_OFFSET_Y,
                AppPreferences.DEFAULT_CENTER_OFFSET_X to AppPreferences.DEFAULT_CENTER_OFFSET_Y,
                controls, 0.dp, 0.dp, 0.dp, 0.dp, platform = platform
            )
            val rightId = if (platform.isModel3) "r2" else "b"
            val right = requireNotNull(layout.button(rightId))
            requireNotNull(layout.button("a")).y.value to (right.x + right.width).value
        }
        positions.forEach { (platform, position) ->
            val (y, rightEdge) = position
            if (platform == EmulationPlatform.DREAMCAST) assertEquals(platform.name, 348.0645f, y, .01f)
            if (platform != EmulationPlatform.DREAMCAST) {
                assertEquals("$platform right margin", 778f, rightEdge, .01f)
            }
        }
    }
}
