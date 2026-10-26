package com.sbro.emucoreh.ui.settings

import com.sbro.emucoreh.R
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.data.GameMenuTabId
import org.junit.Assert.*
import org.junit.Test

class SegaSettingsPolicyTest {
    @Test fun customizationOffersSupportedCheatsWithoutEnablingAchievements() {
        for (platform in EmulationPlatform.entries) {
            assertEquals("Cheats availability for $platform", !platform.isModel3 && !platform.isSaturn,
                gameMenuCustomizationTabAvailable(platform, GameMenuTabId.CHEATS))
            assertEquals("Achievements availability for $platform", platform.coreId == 0,
                gameMenuCustomizationTabAvailable(platform, GameMenuTabId.ACHIEVEMENTS))
            for (tab in GameMenuTabId.entries.filter { it != GameMenuTabId.CHEATS && it != GameMenuTabId.ACHIEVEMENTS }) {
                assertTrue("Shared $tab tab must remain available for $platform",
                    gameMenuCustomizationTabAvailable(platform, tab))
            }
        }
    }

    @Test fun analogSettingsFollowCoreInputCapabilities() {
        assertTrue(EmulationPlatform.DREAMCAST.supportsAnalogControlSettings())
        assertTrue(EmulationPlatform.MODEL_3.supportsAnalogControlSettings())
        for (platform in EmulationPlatform.entries.filter { it.isSega }) {
            assertFalse(platform.supportsAnalogControlSettings())
        }
    }

    @Test fun model3SearchExposesAnalogSettingsWithoutFlycastFeatures() {
        val analogSettings = listOf(
            R.string.settings_touchscreen_right_stick,
            R.string.settings_touchscreen_right_stick_sensitivity,
            R.string.settings_left_stick_sensitivity,
            R.string.settings_right_stick_sensitivity,
            R.string.settings_invert_right_stick,
            R.string.settings_invert_right_stick_horizontal,
            R.string.settings_gamepad_right_stick_sensitivity,
            R.string.settings_mouse_aim_mode,
            R.string.settings_mouse_sensitivity
        )
        for (title in analogSettings) {
            assertTrue(settingsSearchEntryAvailable(EmulationPlatform.MODEL_3, "Controls", title))
            assertTrue(settingsSearchEntryAvailable(EmulationPlatform.DREAMCAST, "Controls", title))
            for (platform in EmulationPlatform.entries.filter { it.isSega }) {
                assertFalse(settingsSearchEntryAvailable(platform, "Controls", title))
            }
        }
        for (title in listOf(R.string.settings_renderer,
            R.string.settings_racing_mode, R.string.settings_gyro_mode,
            R.string.settings_arcade_device_player_1, R.string.settings_pad_vibration)) {
            assertFalse(settingsSearchEntryAvailable(EmulationPlatform.MODEL_3, "Controls", title))
            assertTrue(settingsSearchEntryAvailable(EmulationPlatform.DREAMCAST, "Controls", title))
        }
        assertTrue(settingsSearchEntryAvailable(EmulationPlatform.MODEL_3, "Graphics", R.string.settings_upscale))
        for (platform in EmulationPlatform.entries.filter { it.isSega }) {
            assertFalse(settingsSearchEntryAvailable(platform, "Graphics", R.string.settings_upscale))
        }
        for (tab in listOf("Network", "Arcade")) {
            assertFalse(settingsSearchEntryAvailable(EmulationPlatform.MODEL_3, tab, R.string.settings_renderer))
        }
        assertTrue(settingsSearchEntryAvailable(EmulationPlatform.MODEL_3, "About", R.string.settings_about_app_desc))
    }

    @Test fun segaKeepsAppWideSettingsAvailable() {
        for (tab in listOf("Library", "GameMenu", "Customization", "General", "Audio", "About", "Emulation", "Graphics", "Controls")) {
            assertTrue("$tab must stay available", segaSettingsTabAvailable(tab))
        }
        assertFalse(segaSettingsTabAvailable("Network"))
        assertFalse(segaSettingsTabAvailable("Arcade"))
    }
}
