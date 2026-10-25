package com.sbro.emucoreh.ui.settings

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.sbro.emucoreh.ui.common.OverlayCanvasDpadClusterSpec
import com.sbro.emucoreh.ui.common.OverlayDpadClusterSurface
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlsEditorDpadClusterDragTest {
    @Test fun segaConsoleButtonsSelectTheWholeGroupUnlessIndividualEditingIsEnabled() {
        assertEquals("group_dpad", editorDragTarget(com.sbro.emucoreh.core.EmulationPlatform.GENESIS, "dpad_up", false))
        assertEquals("group_actions", editorDragTarget(com.sbro.emucoreh.core.EmulationPlatform.SEGA_CD, "l1", false))
        assertEquals("dpad_up", editorDragTarget(com.sbro.emucoreh.core.EmulationPlatform.GENESIS, "dpad_up", true))
        assertEquals("dpad_up", editorDragTarget(com.sbro.emucoreh.core.EmulationPlatform.DREAMCAST, "dpad_up", false))
        assertEquals("a", editorDragTarget(com.sbro.emucoreh.core.EmulationPlatform.MODEL_3, "a", false))
        assertEquals("start", editorDragTarget(com.sbro.emucoreh.core.EmulationPlatform.GENESIS, "start", false))
    }

    @Test
    fun dragBaseIgnoresTheCurrentClusterOffset() {
        val spec = OverlayCanvasDpadClusterSpec(
            id = "dpad_cluster",
            size = 120.dp,
            baseX = 70.dp,
            baseY = 200.dp,
            x = 130.dp,
            y = 260.dp,
            opacity = 90,
            visible = true,
            surface = OverlayDpadClusterSurface(
                offset = DpOffset(5.dp, 7.dp),
                width = 130.dp,
                height = 130.dp
            )
        )

        val base = dpadClusterDragBase(spec)

        assertEquals(75.dp, base.first)
        assertEquals(207.dp, base.second)
    }
}
