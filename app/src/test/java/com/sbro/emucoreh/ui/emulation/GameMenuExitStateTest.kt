package com.sbro.emucoreh.ui.emulation

import org.junit.Assert.*
import org.junit.Test

class GameMenuExitStateTest {
    private val menu = EmulationUiState(
        isRunning = true, isPaused = true, showMenu = true,
        currentGameTitle = "Soul Calibur", currentGameSubtitle = "T-1401N",
        currentGameCoverPath = "/cache/soul_calibur.online.png"
    )

    @Test fun `shutdown does not remove the exiting menu artwork or its composition`() {
        val exit = GameMenuExitState()
        exit.begin(menu)
        val shown = exit.present(EmulationUiState())
        assertEquals("/cache/soul_calibur.online.png", shown.currentGameCoverPath)
        assertEquals("Soul Calibur", shown.currentGameTitle)
        assertEquals("T-1401N", shown.currentGameSubtitle)
        assertTrue(shown.showMenu)
        assertTrue(shown.isRunning)
        assertTrue(shown.isActionInProgress)
    }

    @Test fun `exiting directly from gameplay does not flash an unopened menu`() {
        val exit = GameMenuExitState()
        exit.begin(menu.copy(showMenu = false))
        val stopped = EmulationUiState()
        assertSame(stopped, exit.present(stopped))
    }

    @Test fun `normal menu close continues to follow live state`() {
        val exit = GameMenuExitState()
        val closed = menu.copy(showMenu = false)
        assertSame(closed, exit.present(closed))
    }

    @Test fun `fresh exit or session reset cannot show another games cover`() {
        val exit = GameMenuExitState()
        exit.begin(menu)
        exit.reset()
        val next = menu.copy(currentGameTitle = "Crazy Taxi", currentGameCoverPath = "/cache/crazy_taxi.png")
        assertSame(next, exit.present(next))
        exit.begin(next)
        assertEquals("Crazy Taxi", exit.present(EmulationUiState()).currentGameTitle)
        assertEquals("/cache/crazy_taxi.png", exit.present(EmulationUiState()).currentGameCoverPath)
    }
}
