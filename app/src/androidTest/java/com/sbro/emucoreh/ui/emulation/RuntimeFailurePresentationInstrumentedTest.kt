// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.ui.emulation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sbro.emucoreh.core.RuntimeFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeFailurePresentationInstrumentedTest {
    @Test
    fun failedExitCannotOverwriteTheAutosaveOrChangeTheUsersPreference() {
        for (enabled in listOf(false, true)) {
            val state = EmulationUiState(autoSaveOnExit = enabled)
            assertEquals(enabled, state.shouldAutoSaveOnExit("owned-fixture.cue", null))
            assertFalse(state.shouldAutoSaveOnExit(null, null))
            assertFalse(state.shouldAutoSaveOnExit("owned-fixture.cue", RuntimeFailure("probe fault")))
            assertFalse(state.shouldAutoSaveOnExit(null, RuntimeFailure("probe fault")))
            assertEquals(enabled, state.autoSaveOnExit)
        }
    }

    @Test
    fun fatalStateDominatesDeferredStartupAndLoadCompletionWithoutMutatingIntent() {
        val intent = EmulationUiState(isRunning = true, isStarting = true,
            isPaused = true, showMenu = true, isActionInProgress = true,
            actionLabel = "loading", statusMessage = "status_running",
            toastMessage = "loaded", fps = "60")
        val failure = RuntimeFailure("probe device lost")
        val shown = intent.withRuntimeFailure(failure)
        assertSame(failure, shown.runtimeFailure)
        assertFalse(shown.isRunning)
        assertFalse(shown.isStarting)
        assertFalse(shown.isPaused)
        assertFalse(shown.showMenu)
        assertFalse(shown.isActionInProgress)
        assertNull(shown.actionLabel)
        assertNull(shown.statusMessage)
        assertNull(shown.toastMessage)
        assertEquals("0", shown.fps)
        // Late completion cannot hide the failure, and cleared runtime state
        // does not permanently rewrite the next session's requested settings.
        assertEquals(shown, intent.copy(isStarting = false).withRuntimeFailure(failure))
        assertSame(intent, intent.withRuntimeFailure(null))
        assertEquals(intent.renderer, shown.renderer)
    }
}
