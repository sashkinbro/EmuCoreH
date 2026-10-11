package com.sbro.emucoreh.ui.common

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.sbro.emucoreh.data.AppPreferences
import com.sbro.emucoreh.data.OverlayControlLayout
import org.junit.Assert.*
import org.junit.Test

class OverlayGunControlsLayoutTest {
    private val gunIds = listOf("gun_trigger", "gun_pedal", "gun_reload", "gun_recalibrate")

    @Test fun hiddenGunControlsAppearInEditorPreviewButStayHiddenDuringPlay() {
        val preview = layout(preview = true)
        val live = layout(preview = false)
        for (id in gunIds) {
            assertNotNull("$id must be selectable in the editor", preview.button(id))
            assertFalse(requireNotNull(preview.button(id)).visible)
            assertNull("$id must honor its hidden setting during play", live.button(id))
        }
    }

    @Test fun visibleGunControlsUseCabinetWidthsAndSeparateRowOnBothOrientations() {
        val controls = AppPreferences.defaultOverlayControlLayouts().toMutableMap()
        (gunIds + listOf("coin", "test", "service")).forEach { id ->
            controls[id] = controls.getValue(id).copy(visible = true)
        }
        for ((width, height) in listOf(800f to 450f, 360f to 800f)) {
            val result = layout(controls, width = width, height = height)
            val guns = gunIds.map { requireNotNull(result.button(it)) }
            val service = requireNotNull(result.button("service"))
            for (gun in guns) {
                assertTrue(gun.visible)
                assertEquals(service.width.value, gun.width.value, 0.001f)
                assertEquals(service.height.value, gun.height.value, 0.001f)
                assertTrue("gun row must clear the service row", gun.y + gun.height < service.y)
                assertTrue(gun.x >= 0.dp)
                assertTrue(gun.x + gun.width <= width.dp)
            }
            guns.zipWithNext().forEach { (left, right) ->
                assertEquals(left.y.value, right.y.value, 0.001f)
                assertTrue("gun controls must not overlap", left.x + left.width < right.x)
            }
        }
    }

    @Test fun gunOffsetsRoundTripForEitherSignAndScalingKeepsAdjacentSlotsStable() {
        val controls = AppPreferences.defaultOverlayControlLayouts().toMutableMap()
        gunIds.forEach { id -> controls[id] = controls.getValue(id).copy(visible = true) }
        val original = layout(controls)
        for (offset in listOf(-27f to 18f, 36f to -21f)) {
            val changed = controls.toMutableMap().apply {
                this["gun_reload"] = getValue("gun_reload").copy(scale = 120, offset = offset, opacity = 47)
            }
            val result = layout(changed)
            val reload = requireNotNull(result.button("gun_reload"))
            assertEquals(offset.first, (reload.x - reload.baseX).value * 3f, 0.001f)
            assertEquals(offset.second, (reload.y - reload.baseY).value * 3f, 0.001f)
            assertEquals(requireNotNull(original.button("gun_reload")).width.value * 1.5f, reload.width.value, 0.001f)
            assertEquals(47, reload.opacity)
            for (id in gunIds.filterNot { it == "gun_reload" }) {
                assertEquals(requireNotNull(original.button(id)).x.value, requireNotNull(result.button(id)).x.value, 0.001f)
            }
            val recovered = (reload.x - reload.baseX).value * 3f to (reload.y - reload.baseY).value * 3f
            changed["gun_reload"] = changed.getValue("gun_reload").copy(offset = recovered)
            assertEquals(reload.x.value, requireNotNull(layout(changed).button("gun_reload")).x.value, 0.001f)
            assertEquals(reload.y.value, requireNotNull(layout(changed).button("gun_reload")).y.value, 0.001f)
        }
    }

    private fun layout(
        controls: Map<String, OverlayControlLayout> = AppPreferences.defaultOverlayControlLayouts(),
        preview: Boolean = false,
        width: Float = 800f,
        height: Float = 450f
    ): OverlayCanvasLayout = buildOverlayCanvasLayout(
        canvasWidth = width.dp, canvasHeight = height.dp, density = Density(3f),
        scaleFactor = 1f, stickScaleFactor = 1f,
        dpadOffset = AppPreferences.DEFAULT_DPAD_OFFSET_X to AppPreferences.DEFAULT_DPAD_OFFSET_Y,
        lstickOffset = AppPreferences.DEFAULT_LSTICK_OFFSET_X to AppPreferences.DEFAULT_LSTICK_OFFSET_Y,
        rstickOffset = AppPreferences.DEFAULT_RSTICK_OFFSET_X to AppPreferences.DEFAULT_RSTICK_OFFSET_Y,
        actionOffset = AppPreferences.DEFAULT_ACTION_OFFSET_X to AppPreferences.DEFAULT_ACTION_OFFSET_Y,
        lbtnOffset = AppPreferences.DEFAULT_LBTN_OFFSET_X to AppPreferences.DEFAULT_LBTN_OFFSET_Y,
        rbtnOffset = AppPreferences.DEFAULT_RBTN_OFFSET_X to AppPreferences.DEFAULT_RBTN_OFFSET_Y,
        centerOffset = AppPreferences.DEFAULT_CENTER_OFFSET_X to AppPreferences.DEFAULT_CENTER_OFFSET_Y,
        controlLayouts = controls, safeLeftInset = 0.dp, safeRightInset = 0.dp,
        safeTopInset = 0.dp, safeBottomInset = 0.dp, previewMode = preview
    )
}
