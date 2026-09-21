package com.sbro.emucoreh.ui.emulation

internal fun completeEmulationExit(
    activePlayTimeMs: Long,
    restoreHostUi: () -> Unit,
    navigateFromEmulation: (Long) -> Unit
) {
    restoreHostUi()
    navigateFromEmulation(activePlayTimeMs)
}
