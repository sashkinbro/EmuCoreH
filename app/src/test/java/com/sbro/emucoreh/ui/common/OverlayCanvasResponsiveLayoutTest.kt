package com.sbro.emucoreh.ui.common

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sbro.emucoreh.data.AppPreferences
import com.sbro.emucoreh.data.OverlayControlLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayCanvasResponsiveLayoutTest {
    private val density = Density(3f)

    @Test
    fun `default dpad cluster arrows keep their in-block slots`() {
        val layout = buildLayout(ScreenCase("default dpad arrows", 800.dp, 450.dp))
        val dpad = requireNotNull(layout.dpadCluster)

        OverlayDpadDirection.entries.forEach { direction ->
            val offset = requireNotNull(dpad.directionOffsets[direction])
            assertEquals(0f, offset.x.value, EPSILON)
            assertEquals(0f, offset.y.value, EPSILON)
        }
        assertEquals(0f, dpad.surface.offset.x.value, EPSILON)
        assertEquals(0f, dpad.surface.offset.y.value, EPSILON)
        assertEquals(dpad.size.value, dpad.surface.width.value, EPSILON)
        assertEquals(dpad.size.value, dpad.surface.height.value, EPSILON)
    }

    @Test
    fun `dpad cluster arrows follow per-control offsets`() {
        val screen = ScreenCase("dpad arrow offsets", 800.dp, 450.dp)
        val customControls = AppPreferences.defaultOverlayControlLayouts().toMutableMap().apply {
            this["dpad_up"] = requireNotNull(this["dpad_up"]).copy(offset = 0f to 9f)
            this["dpad_right"] = requireNotNull(this["dpad_right"]).copy(offset = -12f to 0f)
        }
        val layout = buildLayout(screen, customControls)
        val dpad = requireNotNull(layout.dpadCluster)

        assertEquals(3f, dpad.directionOffsets.getValue(OverlayDpadDirection.Up).y.value, EPSILON)
        assertEquals(0f, dpad.directionOffsets.getValue(OverlayDpadDirection.Up).x.value, EPSILON)
        assertEquals(-4f, dpad.directionOffsets.getValue(OverlayDpadDirection.Right).x.value, EPSILON)
        assertEquals(0f, dpad.directionOffsets.getValue(OverlayDpadDirection.Right).y.value, EPSILON)
    }

    @Test
    fun `dpad cluster surface grows around arrows moved outside the base square`() {
        val screen = ScreenCase("dpad surface", 800.dp, 450.dp)
        val customControls = AppPreferences.defaultOverlayControlLayouts().toMutableMap().apply {
            this["dpad_left"] = requireNotNull(this["dpad_left"]).copy(offset = -30f to 0f)
            this["dpad_right"] = requireNotNull(this["dpad_right"]).copy(offset = 0f to 300f)
        }
        val layout = buildLayout(screen, customControls)
        val dpad = requireNotNull(layout.dpadCluster)
        val surface = dpad.surface
        val arrowSize = dpad.size * OverlayDpadClusterArrowScale

        assertTrue(surface.offset.x < 0.dp)
        assertTrue(surface.width > dpad.size)
        assertTrue(surface.height > dpad.size)
        OverlayDpadDirection.entries.forEach { direction ->
            val anchor = overlayDpadArrowDefault(direction, dpad.size) +
                dpad.directionOffsets.getValue(direction)
            assertTrue(anchor.x.value >= surface.offset.x.value - EPSILON)
            assertTrue(anchor.y.value >= surface.offset.y.value - EPSILON)
            assertTrue(
                (anchor.x + arrowSize).value <=
                    (surface.offset.x + surface.width).value + EPSILON
            )
            assertTrue(
                (anchor.y + arrowSize).value <=
                    (surface.offset.y + surface.height).value + EPSILON
            )
        }
    }

    @Test
    fun `hiding the independent dpad keeps the left stick in place`() {
        val screen = ScreenCase("stable left stick", 800.dp, 450.dp)
        val defaults = buildLayout(screen)
        val hiddenControls = AppPreferences.defaultOverlayControlLayouts().toMutableMap().apply {
            this["dpad_cluster"] = requireNotNull(this["dpad_cluster"]).copy(visible = false)
        }
        val hidden = buildLayout(screen, hiddenControls)
        val defaultStick = requireNotNull(defaults.leftStick)
        val hiddenStick = requireNotNull(hidden.leftStick)

        assertTrue(hidden.dpadCluster == null || hidden.dpadCluster?.visible == false)
        assertEquals(defaultStick.x.value, hiddenStick.x.value, EPSILON)
        assertEquals(defaultStick.y.value, hiddenStick.y.value, EPSILON)
    }

    private fun buildLayout(
        screen: ScreenCase,
        controls: Map<String, OverlayControlLayout> = AppPreferences.defaultOverlayControlLayouts()
    ): OverlayCanvasLayout = buildOverlayCanvasLayout(
        canvasWidth = screen.width,
        canvasHeight = screen.height,
        density = density,
        scaleFactor = 1f,
        stickScaleFactor = 1f,
        dpadOffset = AppPreferences.DEFAULT_DPAD_OFFSET_X to AppPreferences.DEFAULT_DPAD_OFFSET_Y,
        lstickOffset = AppPreferences.DEFAULT_LSTICK_OFFSET_X to AppPreferences.DEFAULT_LSTICK_OFFSET_Y,
        rstickOffset = AppPreferences.DEFAULT_RSTICK_OFFSET_X to AppPreferences.DEFAULT_RSTICK_OFFSET_Y,
        actionOffset = AppPreferences.DEFAULT_ACTION_OFFSET_X to AppPreferences.DEFAULT_ACTION_OFFSET_Y,
        lbtnOffset = AppPreferences.DEFAULT_LBTN_OFFSET_X to AppPreferences.DEFAULT_LBTN_OFFSET_Y,
        rbtnOffset = AppPreferences.DEFAULT_RBTN_OFFSET_X to AppPreferences.DEFAULT_RBTN_OFFSET_Y,
        centerOffset = AppPreferences.DEFAULT_CENTER_OFFSET_X to AppPreferences.DEFAULT_CENTER_OFFSET_Y,
        controlLayouts = controls,
        safeLeftInset = screen.leftInset,
        safeRightInset = screen.rightInset,
        safeTopInset = screen.topInset,
        safeBottomInset = screen.bottomInset
    )

    private data class ScreenCase(
        val name: String,
        val width: Dp,
        val height: Dp,
        val leftInset: Dp = 0.dp,
        val rightInset: Dp = 0.dp,
        val topInset: Dp = 0.dp,
        val bottomInset: Dp = 0.dp
    )

    private companion object {
        const val EPSILON = 0.01f
    }
}
