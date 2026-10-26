package com.sbro.emucoreh.ui.common

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.data.AppPreferences
import com.sbro.emucoreh.data.OverlayControlLayout
import com.sbro.emucoreh.data.SegaTouchLayouts
import org.junit.Assert.*
import org.junit.Test

class Model3OverlayCanvasLayoutTest {
    private data class Rect(val id: String, val x: Float, val y: Float, val w: Float, val h: Float)

    @Test fun model3DefaultsHaveSeparateButtonStickAndSystemSlots() {
        for ((w, h) in listOf(640f to 360f, 800f to 450f, 360f to 800f, 240f to 160f, 1280f to 720f)) {
            for (inset in listOf(0f, 12f)) for (scale in listOf(1f, 1.5f, 2f)) {
                val result = layout(w, h, inset, scale)
                val rects = result.allButtons.map { Rect(it.id, it.x.value, it.y.value, it.width.value, it.height.value) } +
                    listOfNotNull(result.leftStick, result.rightStick).map {
                        val width = it.size.value * (it.widthScale / 100f).coerceAtLeast(1f)
                        Rect(it.id, it.x.value - (width - it.size.value) / 2, it.y.value, width, it.size.value)
                    }
                rects.forEach { a ->
                    assertTrue("$w x $h ${a.id} outside safe area: $a", a.x >= inset && a.y >= inset &&
                        a.x + a.w <= w - inset + .01f && a.y + a.h <= h - inset + .01f)
                    rects.filter { it.id > a.id }.forEach { b ->
                        val separation = maxOf(b.x - a.x - a.w, a.x - b.x - b.w,
                            b.y - a.y - a.h, a.y - b.y - b.h)
                        // The Dreamcast-style D-pad intentionally overlaps adjacent
                        // arrow hitboxes while the visible arrow shapes stay apart.
                        if (!(a.id.startsWith("dpad_") && b.id.startsWith("dpad_"))) {
                            assertTrue("$w x $h scale=$scale ${a.id}/${b.id} gap=$separation", separation >= 3.99f)
                        }
                    }
                }
                assertEquals(setOf("select", "start", "l3", "r3"), result.centerButtons.map { it.id }.toSet())
                assertTrue(result.centerButtons.all { system ->
                    result.actionButtons.all { it.y + it.height <= system.y }
                })
                assertEquals(result, layout(w, h, inset, scale, preview = true))
            }
        }
    }

    @Test fun model3SavedOffsetsRemainRelativeToTheirSlotsAndClampAtSafeEdges() {
        val defaults = SegaTouchLayouts.defaultProfile(EmulationPlatform.MODEL_3).controlLayouts
        val moved = defaults + ("left_stick" to defaults.getValue("left_stick").copy(offset = 18f to -12f)) +
            ("a" to defaults.getValue("a").copy(offset = -18f to -12f))
        val result = layout(800f, 450f, 12f, controls = moved)
        val stick = requireNotNull(result.leftStick)
        assertEquals(6f, (stick.x - stick.baseX).value, .001f)
        assertEquals(-4f, (stick.y - stick.baseY).value, .001f)
        assertEquals(-6f, (result.button("a")!!.x - result.button("a")!!.baseX).value, .001f)
        val extreme = moved.mapValues { it.value.copy(offset = Float.POSITIVE_INFINITY to -100000f) }
        val clamped = layout(360f, 800f, 12f, controls = extreme)
        val clampedStick = requireNotNull(clamped.leftStick)
        assertTrue(clampedStick.x.value.isFinite())
        assertTrue(clampedStick.y >= 24.dp)
        assertTrue(clamped.allButtons.all { it.x.value.isFinite() && it.y >= 24.dp })
    }

    @Test fun model3SavedSizesVisibilityAndWideStickPanelsRemainUsable() {
        val defaults = SegaTouchLayouts.defaultProfile(EmulationPlatform.MODEL_3).controlLayouts
        val custom = defaults + ("right_stick" to defaults.getValue("right_stick").copy(
            scale = 180, widthScale = 180, opacity = 37)) +
            ("a" to defaults.getValue("a").copy(visible = false, scale = 150, opacity = 42))
        for ((w, h) in listOf(240f to 160f, 360f to 800f, 800f to 450f)) {
            val runtime = layout(w, h, 12f, controls = custom)
            val preview = layout(w, h, 12f, controls = custom, preview = true)
            assertNull(runtime.button("a"))
            assertEquals(42, preview.button("a")!!.opacity)
            assertEquals(preview.rightStick, runtime.rightStick)
            val right = requireNotNull(runtime.rightStick)
            val left = requireNotNull(runtime.leftStick)
            assertEquals(37, right.opacity)
            assertEquals(180, right.widthScale)
            assertEquals(1.8f, right.size.value / left.size.value, .001f)
            val panel = right.size.value * 1.8f
            assertTrue(right.x.value - (panel - right.size.value) / 2 >= 24f - .001f)
            assertTrue(right.x.value + (panel + right.size.value) / 2 <= w - 24f + .001f)
        }
    }

    @Test fun model3SavedGroupOffsetsRemainAppliedToButtonsAndBottomSystemRow() {
        val original = layout(800f, 450f, 12f)
        val moved = layout(800f, 450f, 12f, groupOffset = 9f to -9f)
        for (id in listOf("dpad_up", "a", "start", "l3", "r3")) {
            assertEquals("$id baseX", 3f, (moved.button(id)!!.baseX - original.button(id)!!.baseX).value, .001f)
            assertEquals("$id baseY", -3f, (moved.button(id)!!.baseY - original.button(id)!!.baseY).value, .001f)
        }
    }

    @Test fun model3DpadMatchesDreamcastCrossGeometry() {
        val result = layout(800f, 450f, 12f)
        val up = requireNotNull(result.button("dpad_up"))
        val left = requireNotNull(result.button("dpad_left"))
        val right = requireNotNull(result.button("dpad_right"))
        val down = requireNotNull(result.button("dpad_down"))
        val expected = overlayDpadCrossGeometry(130f, true)
        val horizontalExtent = (right.x + right.width) - left.x
        val verticalExtent = (down.y + down.height) - up.y
        assertEquals(expected.tile, up.width.value, .001f)
        assertEquals(expected.extent, horizontalExtent.value, .001f)
        assertEquals(expected.extent, verticalExtent.value, .001f)
    }

    private fun layout(w: Float, h: Float, inset: Float, scale: Float = 1f,
        controls: Map<String, OverlayControlLayout> = SegaTouchLayouts.defaultProfile(EmulationPlatform.MODEL_3).controlLayouts,
        preview: Boolean = false, groupOffset: Pair<Float, Float> = 0f to 0f
    ) = buildOverlayCanvasLayout(w.dp, h.dp, Density(3f), scale, scale,
        AppPreferences.DEFAULT_DPAD_OFFSET_X + groupOffset.first to AppPreferences.DEFAULT_DPAD_OFFSET_Y + groupOffset.second,
        AppPreferences.DEFAULT_LSTICK_OFFSET_X to AppPreferences.DEFAULT_LSTICK_OFFSET_Y,
        AppPreferences.DEFAULT_RSTICK_OFFSET_X to AppPreferences.DEFAULT_RSTICK_OFFSET_Y,
        AppPreferences.DEFAULT_ACTION_OFFSET_X + groupOffset.first to AppPreferences.DEFAULT_ACTION_OFFSET_Y + groupOffset.second,
        AppPreferences.DEFAULT_LBTN_OFFSET_X to AppPreferences.DEFAULT_LBTN_OFFSET_Y,
        AppPreferences.DEFAULT_RBTN_OFFSET_X to AppPreferences.DEFAULT_RBTN_OFFSET_Y,
        AppPreferences.DEFAULT_CENTER_OFFSET_X + groupOffset.first to AppPreferences.DEFAULT_CENTER_OFFSET_Y + groupOffset.second,
        controls, inset.dp, inset.dp, inset.dp, inset.dp, preview, EmulationPlatform.MODEL_3)
}
