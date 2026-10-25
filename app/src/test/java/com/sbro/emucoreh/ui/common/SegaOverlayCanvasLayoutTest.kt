package com.sbro.emucoreh.ui.common

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.data.AppPreferences
import com.sbro.emucoreh.data.OverlayControlLayout
import com.sbro.emucoreh.data.SegaTouchLayouts
import org.junit.Assert.*
import org.junit.Test

class SegaOverlayCanvasLayoutTest {
    @Test fun repeatedGrowAndShrinkRebuildsPreserveConsoleGroupSpacing() {
        val platform = EmulationPlatform.GENESIS
        for ((width, height) in listOf(800f to 450f, 360f to 800f, 240f to 160f)) {
            for (dpad in listOf(true, false)) {
                var controls = SegaTouchLayouts.defaultProfile(platform).controlLayouts
                val original = layout(platform, controls, width = width, height = height)
                val originalGroup = if (dpad) original.dpadButtons else original.actionButtons
                repeat(3) {
                    for (factor in listOf(1.4f, 1f / 1.4f)) {
                        val current = layout(platform, controls, width = width, height = height)
                        val group = if (dpad) current.dpadButtons else current.actionButtons
                        val members = group.map { spec ->
                            val control = controls.getValue(spec.id)
                            OverlayGroupScaleMember(spec.id, spec.baseX.value * 3f, spec.baseY.value * 3f,
                                control.offset.first, control.offset.second, control.scale,
                                spec.width.value * 3f, spec.height.value * 3f,
                                requireNotNull(spec.resizeCenter).x.value * 3f, spec.resizeCenter.y.value * 3f)
                        }
                        val updates = scaleOverlayControlGroup(members, factor, 50, 500, width * 3f, height * 3f,
                            preserveSegaGeometry = true, safeLeft = 84f, safeRight = 84f, safeTop = 60f, safeBottom = 60f)
                        controls = controls + updates.associate { it.controlId to controls.getValue(it.controlId).copy(scale = it.scale, offset = it.offset) }
                        val rebuilt = layout(platform, controls, width = width, height = height)
                        val origin = requireNotNull(rebuilt.button(originalGroup.first().id))
                        originalGroup.forEach { old ->
                            val button = requireNotNull(rebuilt.button(old.id))
                            val actualFactor = controls.getValue(old.id).scale / 100f
                            assertEquals((old.x - originalGroup.first().x).value * actualFactor, (button.x - origin.x).value, .001f)
                            assertEquals((old.y - originalGroup.first().y).value * actualFactor, (button.y - origin.y).value, .001f)
                            assertTrue(button.x >= 28.dp && button.y >= 20.dp)
                            assertTrue(button.x + button.width <= (width - 28f + .001f).dp)
                            assertTrue(button.y + button.height <= (height - 20f + .001f).dp)
                        }
                    }
                    originalGroup.forEach { assertEquals(100, controls.getValue(it.id).scale) }
                }
            }
        }
    }

    @Test fun scalingConsoleGroupsKeepsWholeGeometryInsideSafeAreaAfterRebuild() {
        val platform = EmulationPlatform.GENESIS
        for ((width, height) in listOf(800f to 450f, 360f to 800f, 240f to 160f)) {
            for (dpad in listOf(true, false)) {
              for (requestedFactor in listOf(1.4f, 100f)) {
                val controls = SegaTouchLayouts.defaultProfile(platform).controlLayouts
                val before = layout(platform, controls, width = width, height = height)
                val group = if (dpad) before.dpadButtons else before.actionButtons
                val members = group.map { spec -> OverlayGroupScaleMember(spec.id,
                    spec.baseX.value * 3f, spec.baseY.value * 3f, 0f, 0f, 100, spec.width.value * 3f, spec.height.value * 3f) }
                val updates = scaleOverlayControlGroup(members, requestedFactor, 50, 500, width * 3f, height * 3f,
                    preserveSegaGeometry = true, safeLeft = 84f, safeRight = 84f, safeTop = 60f, safeBottom = 60f)
                val changed = controls + updates.associate { it.controlId to OverlayControlLayout(scale = it.scale, offset = it.offset) }
                val after = layout(platform, changed, width = width, height = height)
                val firstBefore = group.first()
                val firstAfter = requireNotNull(after.button(firstBefore.id))
                val factor = updates.first().scale / 100f
                group.forEach { old ->
                    val current = requireNotNull(after.button(old.id))
                    assertEquals((old.x - firstBefore.x).value * factor, (current.x - firstAfter.x).value, .001f)
                    assertEquals((old.y - firstBefore.y).value * factor, (current.y - firstAfter.y).value, .001f)
                    assertTrue(current.x >= 28.dp && current.y >= 20.dp)
                    assertTrue(current.x + current.width <= (width - 28f + .001f).dp)
                    assertTrue(current.y + current.height <= (height - 20f + .001f).dp)
                }
              }
            }
        }
    }

    @Test fun editorAndRuntimeUseIdenticalSlotsAndPreserveHiddenEditing() {
        EmulationPlatform.entries.filter { it.isSega }.forEach { platform ->
            val defaults = SegaTouchLayouts.defaultProfile(platform).controlLayouts
            val changed = defaults + ("a" to OverlayControlLayout(offset = -48f to -30f, scale = 135, widthScale = 120, opacity = 42))
            val preview = layout(platform, changed, true)
            assertEquals(preview, layout(platform, changed, false))
            val a = requireNotNull(preview.button("a"))
            assertEquals(-48f, (a.x - a.baseX).value * 3f, .001f)
            assertEquals(-30f, (a.y - a.baseY).value * 3f, .001f)
            assertEquals(42, a.opacity)
            val hidden = changed + ("a" to changed.getValue("a").copy(visible = false))
            assertNotNull(layout(platform, hidden, true).button("a"))
            assertNull(layout(platform, hidden, false).button("a"))
            assertNull(preview.leftStick)
            assertNull(preview.rightStick)
        }
    }

    @Test fun defaultsFitSafeAreaAndKeepSixButtonGridTogether() {
        val result = layout(EmulationPlatform.GENESIS)
        assertEquals(setOf("x", "a", "b", "l1", "y", "r1"), result.actionButtons.map { it.id }.toSet())
        result.allButtons.forEach { button ->
            assertTrue(button.x >= 28.dp)
            assertTrue(button.y >= 20.dp)
            assertTrue(button.x + button.width <= 772.dp)
            assertTrue(button.y + button.height <= 430.dp)
        }
    }

    @Test fun savedExtremeScaleOffsetsAndGroupOffsetsStayInSafeViewportAfterRotation() {
        EmulationPlatform.entries.filter { it.isSega }.forEach { platform ->
            for ((width, height) in listOf(800f to 450f, 360f to 800f, 240f to 160f)) {
                for (offset in listOf(-100000f to 100000f, 100000f to -100000f, Float.NaN to Float.POSITIVE_INFINITY)) {
                    val controls = SegaTouchLayouts.defaultProfile(platform).controlLayouts.mapValues {
                        it.value.copy(scale = 10000, widthScale = 10000, offset = offset)
                    }
                    val result = layout(platform, controls, width = width, height = height, groupOffset = offset)
                    assertEquals(controls.size, result.allButtons.size)
                    result.allButtons.forEach { button ->
                        assertTrue(button.width.value.isFinite() && button.height.value.isFinite())
                        assertTrue(button.x >= 28.dp && button.y >= 20.dp)
                        assertTrue(button.x + button.width <= (width - 28f + .001f).dp)
                        assertTrue(button.y + button.height <= (height - 20f + .001f).dp)
                    }
                }
            }
        }
    }

    private fun layout(platform: EmulationPlatform,
        controls: Map<String, OverlayControlLayout> = SegaTouchLayouts.defaultProfile(platform).controlLayouts,
        preview: Boolean = false,
        width: Float = 800f,
        height: Float = 450f,
        groupOffset: Pair<Float, Float> = 0f to 0f
    ): OverlayCanvasLayout = buildOverlayCanvasLayout(
        width.dp, height.dp, Density(3f), 1f, 1f,
        (AppPreferences.DEFAULT_DPAD_OFFSET_X + groupOffset.first) to (AppPreferences.DEFAULT_DPAD_OFFSET_Y + groupOffset.second),
        AppPreferences.DEFAULT_LSTICK_OFFSET_X to AppPreferences.DEFAULT_LSTICK_OFFSET_Y,
        AppPreferences.DEFAULT_RSTICK_OFFSET_X to AppPreferences.DEFAULT_RSTICK_OFFSET_Y,
        (AppPreferences.DEFAULT_ACTION_OFFSET_X + groupOffset.first) to (AppPreferences.DEFAULT_ACTION_OFFSET_Y + groupOffset.second),
        AppPreferences.DEFAULT_LBTN_OFFSET_X to AppPreferences.DEFAULT_LBTN_OFFSET_Y,
        AppPreferences.DEFAULT_RBTN_OFFSET_X to AppPreferences.DEFAULT_RBTN_OFFSET_Y,
        (AppPreferences.DEFAULT_CENTER_OFFSET_X + groupOffset.first) to (AppPreferences.DEFAULT_CENTER_OFFSET_Y + groupOffset.second),
        controls, 16.dp, 16.dp, 8.dp, 8.dp, preview, platform
    )
}
