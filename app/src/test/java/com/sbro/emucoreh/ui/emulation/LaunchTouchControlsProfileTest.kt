package com.sbro.emucoreh.ui.emulation

import com.sbro.emucoreh.data.CustomTouchControl
import com.sbro.emucoreh.data.CustomTouchControlLibrary
import com.sbro.emucoreh.data.OverlayControlLayout
import com.sbro.emucoreh.data.TouchControlsLayoutProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LaunchTouchControlsProfileTest {
    @Test
    fun perGameLayoutReplacesTheGlobalOneBeforeLaunch() {
        val state = EmulationUiState()
        val profile = TouchControlsLayoutProfile(
            dpadOffset = 12f to 34f,
            controlLayouts = mapOf("cross" to OverlayControlLayout(offset = 5f to 6f))
        )

        val updated = state.withTouchControlsProfile(layout = profile, customControls = null)

        assertEquals(12f to 34f, updated.dpadOffset)
        assertEquals(5f to 6f, updated.controlLayouts.getValue("cross").offset)
    }

    @Test
    fun perGameCustomControlsReplaceTheGlobalLibraryBeforeLaunch() {
        val state = EmulationUiState()
        val library = CustomTouchControlLibrary(
            controls = listOf(CustomTouchControl(id = "jump", name = "Jump", actionId = "cross"))
        )

        val updated = state.withTouchControlsProfile(layout = null, customControls = library)

        assertEquals(library, updated.customTouchControls)
    }

    @Test
    fun gamesWithoutTouchOverridesKeepTheGlobalState() {
        val state = EmulationUiState()

        val updated = state.withTouchControlsProfile(layout = null, customControls = null)

        assertSame(state, updated)
    }
}
