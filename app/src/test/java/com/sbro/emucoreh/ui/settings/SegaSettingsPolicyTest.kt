package com.sbro.emucoreh.ui.settings

import org.junit.Assert.*
import org.junit.Test

class SegaSettingsPolicyTest {
    @Test fun segaKeepsAppWideSettingsAvailable() {
        for (tab in listOf("Library", "GameMenu", "Customization", "General", "Audio", "About", "Emulation", "Graphics", "Controls")) {
            assertTrue("$tab must stay available", segaSettingsTabAvailable(tab))
        }
        assertFalse(segaSettingsTabAvailable("Network"))
        assertFalse(segaSettingsTabAvailable("Arcade"))
    }
}
