package com.sbro.emucoreh.ui.settings

/** Only unsupported system tabs are hidden; shared tab content uses individual option gates. */
internal fun segaSettingsTabAvailable(tab: String): Boolean = tab !in setOf("Network", "Arcade")
