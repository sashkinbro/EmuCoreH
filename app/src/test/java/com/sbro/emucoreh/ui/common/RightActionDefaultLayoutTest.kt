package com.sbro.emucoreh.ui.common

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.data.AppPreferences
import com.sbro.emucoreh.data.SegaTouchLayouts
import org.junit.Assert.assertEquals
import org.junit.Test

class RightActionDefaultLayoutTest {
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
            requireNotNull(layout.button("a")).y.value
        }
        positions.forEach { (platform, y) ->
            val expected = when (platform) {
                EmulationPlatform.DREAMCAST -> 348.0645f
                EmulationPlatform.GENESIS, EmulationPlatform.SEGA_CD,
                EmulationPlatform.SEGA_32X, EmulationPlatform.SATURN -> 330.08002f
                EmulationPlatform.MASTER_SYSTEM, EmulationPlatform.GAME_GEAR,
                EmulationPlatform.SG_1000 -> 341.6f
                EmulationPlatform.MODEL_3 -> 333.6f
            }
            assertEquals(platform.name, expected, y, .01f)
        }
    }
}
