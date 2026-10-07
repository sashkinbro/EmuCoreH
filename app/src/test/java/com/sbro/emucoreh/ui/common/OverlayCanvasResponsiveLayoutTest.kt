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

    @Test
    fun `stick toggle dpad shares the stick position and hides the automatic dpad`() {
        val screen = ScreenCase("toggle dpad", 800.dp, 450.dp)
        val visibleStickControls = AppPreferences.defaultOverlayControlLayouts().toMutableMap().apply {
            this["left_stick"] = requireNotNull(this["left_stick"]).copy(offset = 40f to -20f)
        }
        val toggledControls = AppPreferences.defaultOverlayControlLayouts().toMutableMap().apply {
            this["dpad_cluster"] = requireNotNull(this["dpad_cluster"]).copy(visible = false)
            this["left_stick"] = requireNotNull(this["left_stick"]).copy(
                visible = false,
                offset = 40f to -20f
            )
            this["dpad_toggle"] = requireNotNull(this["dpad_toggle"]).copy(visible = true)
        }

        val visibleLayout = buildLayout(screen, visibleStickControls)
        val toggledLayout = buildLayout(screen, toggledControls)
        val stick = requireNotNull(visibleLayout.leftStick)
        val toggleDpad = requireNotNull(toggledLayout.toggleDpad)

        assertEquals("left_stick", toggleDpad.replacesStickId)
        assertEquals(stick.x.value, toggleDpad.x.value, EPSILON)
        assertEquals(stick.y.value, toggleDpad.y.value, EPSILON)
        assertTrue(
            "automatic dpad must stay hidden while the toggle dpad is active",
            toggledLayout.dpadButtons.isEmpty()
        )
    }

    @Test
    fun `scaling the extra dpad does not move the left stick`() {
        val screen = ScreenCase("scaled extra dpad", 800.dp, 450.dp)
        val defaults = buildLayout(
            screen,
            AppPreferences.defaultOverlayControlLayouts().toMutableMap().apply {
                this["dpad_cluster"] = requireNotNull(this["dpad_cluster"]).copy(visible = false)
            }
        )
        val scaled = buildLayout(
            screen,
            AppPreferences.defaultOverlayControlLayouts().toMutableMap().apply {
                this["dpad_cluster"] = requireNotNull(this["dpad_cluster"]).copy(scale = 220)
            }
        )
        val defaultStick = requireNotNull(defaults.leftStick)
        val scaledStick = requireNotNull(scaled.leftStick)

        assertEquals(defaultStick.x.value, scaledStick.x.value, EPSILON)
        assertEquals(defaultStick.y.value, scaledStick.y.value, EPSILON)
    }

    @Test
    fun `scaling the left stick does not move the dpad or action clusters`() {
        val screen = ScreenCase("scaled stick", 800.dp, 450.dp)
        val defaults = buildLayout(screen)
        val scaled = buildLayout(
            screen,
            AppPreferences.defaultOverlayControlLayouts().toMutableMap().apply {
                this["left_stick"] = requireNotNull(this["left_stick"]).copy(scale = 200)
            }
        )
        val defaultDpad = requireNotNull(defaults.dpadCluster)
        val scaledDpad = requireNotNull(scaled.dpadCluster)
        val defaultFace = requireNotNull(defaults.button("y"))
        val scaledFace = requireNotNull(scaled.button("y"))

        assertEquals(defaultDpad.x.value, scaledDpad.x.value, EPSILON)
        assertEquals(defaultDpad.y.value, scaledDpad.y.value, EPSILON)
        assertEquals(defaultFace.x.value, scaledFace.x.value, EPSILON)
        assertEquals(defaultFace.y.value, scaledFace.y.value, EPSILON)
    }

    @Test
    fun `scaling a centre button keeps its neighbours on their slots`() {
        val screen = ScreenCase("scaled centre button", 800.dp, 450.dp)
        val visibleSelect = AppPreferences.defaultOverlayControlLayouts().toMutableMap().apply {
            this["select"] = requireNotNull(this["select"]).copy(visible = true)
        }
        val defaults = buildLayout(screen, visibleSelect)
        val scaled = buildLayout(
            screen,
            visibleSelect.toMutableMap().apply {
                this["left_input_toggle"] = requireNotNull(this["left_input_toggle"]).copy(scale = 180)
            }
        )
        listOf("select", "start").forEach { id ->
            val defaultButton = requireNotNull(defaults.button(id))
            val scaledButton = requireNotNull(scaled.button(id))
            assertEquals("$id must keep its slot", defaultButton.x.value, scaledButton.x.value, EPSILON)
        }
    }

    @Test
    fun `asymmetric insets keep equal edge padding on both sides`() {
        val screen = ScreenCase(
            name = "asymmetric cutout",
            width = 800.dp,
            height = 450.dp,
            leftInset = 40.dp,
            rightInset = 10.dp
        )
        val layout = buildLayout(screen)
        val dpad = requireNotNull(layout.dpadCluster)
        val rightActionEdge = layout.actionButtons.maxOf { it.x + it.width }
        val leftMargin = dpad.x.value
        val rightMargin = (screen.width - rightActionEdge).value

        assertTrue("the controls must stay clear of the cutout", dpad.x >= screen.leftInset)
        assertTrue(
            "the controls must stay clear of the right inset",
            rightActionEdge <= screen.width - screen.rightInset
        )
        assertEquals(
            "both sides must keep the same edge padding",
            leftMargin - screen.leftInset.value,
            rightMargin - screen.rightInset.value,
            EPSILON
        )
        assertTrue("the cutout side must keep the larger margin", leftMargin > rightMargin)
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
