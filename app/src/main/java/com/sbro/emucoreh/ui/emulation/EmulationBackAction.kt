package com.sbro.emucoreh.ui.emulation

internal enum class EmulationBackAction {
    OpenGameMenu,
    RequestExit
}

internal fun resolveEmulationBackAction(backButtonExitsGame: Boolean): EmulationBackAction =
    if (backButtonExitsGame) EmulationBackAction.RequestExit else EmulationBackAction.OpenGameMenu
