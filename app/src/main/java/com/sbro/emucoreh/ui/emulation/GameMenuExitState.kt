package com.sbro.emucoreh.ui.emulation

/** Keeps menu presentation alive while the emulator stops and navigation exits. */
internal class GameMenuExitState {
    private var retained: EmulationUiState? = null
    fun begin(state: EmulationUiState) {
        retained = state.takeIf { it.isRunning && it.showMenu }?.copy(isActionInProgress = true)
    }
    fun present(live: EmulationUiState): EmulationUiState = retained ?: live
    fun reset() { retained = null }
}
