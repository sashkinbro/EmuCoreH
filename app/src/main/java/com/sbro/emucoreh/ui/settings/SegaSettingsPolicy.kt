package com.sbro.emucoreh.ui.settings

import com.sbro.emucoreh.R
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.core.supportsInternalResolution
import com.sbro.emucoreh.data.GameMenuTabId

/** Only unsupported system tabs are hidden; shared tab content uses individual option gates. */
internal fun segaSettingsTabAvailable(tab: String): Boolean = tab !in setOf("Network", "Arcade")

internal fun EmulationPlatform.supportsAnalogControlSettings(): Boolean = coreId == 0 || isModel3

internal fun settingsCoreOptionSectionVisible(platform: EmulationPlatform, category: String): Boolean {
    val options = if (platform.isSaturn) com.sbro.emucoreh.core.SaturnCoreOptions.options(platform)
        else com.sbro.emucoreh.core.SegaCoreOptions.options(platform)
    return options.any { it.category == category }
}

internal fun gameMenuCustomizationTabAvailable(platform: EmulationPlatform, tab: GameMenuTabId): Boolean =
    when (tab) {
        GameMenuTabId.CHEATS -> platform.coreId == 0 || platform.isSega
        GameMenuTabId.ACHIEVEMENTS -> platform.coreId == 0
        else -> true
    }

/** Search must expose the same core capabilities as the settings rows it opens. */
internal fun settingsSearchEntryAvailable(platform: EmulationPlatform, tab: String, title: Int): Boolean {
    if (title in saturnSearchSettings) return platform.isSaturn
    if (platform.coreId == 0) return true
    if (title == R.string.settings_upscale) return segaSettingsTabAvailable(tab) && platform.supportsInternalResolution
    if (!segaSettingsTabAvailable(tab) || title in flycastOnlySearchSettings) return false
    if (title == R.string.settings_renderer && platform.isSaturn) return false
    if (title == R.string.settings_renderer && platform.isModel3) return tab == "Graphics"
    return platform.supportsAnalogControlSettings() || title !in analogSearchSettings
}

private val saturnSearchSettings = setOf(
    R.string.saturn_addon_cart,
    R.string.saturn_cd_image_cache,
    R.string.saturn_midsync
)

private val analogSearchSettings = setOf(
    R.string.settings_touchscreen_right_stick, R.string.settings_touchscreen_right_stick_sensitivity,
    R.string.settings_left_stick_sensitivity, R.string.settings_right_stick_sensitivity,
    R.string.settings_invert_right_stick, R.string.settings_invert_right_stick_horizontal,
    R.string.settings_gamepad_right_stick_sensitivity,
    R.string.settings_mouse_aim_mode, R.string.settings_mouse_sensitivity
)

private val flycastOnlySearchSettings = setOf(
    R.string.settings_racing_mode,
    R.string.settings_gyro_mode, R.string.settings_gyro_mode_light_gun, R.string.settings_gyro_stick,
    R.string.settings_arcade_device_player_1, R.string.settings_arcade_device_player_2,
    R.string.settings_light_gun_aim, R.string.settings_light_gun_cursor,
    R.string.settings_gyro_sensitivity, R.string.settings_gyro_smoothing,
    R.string.settings_pad_vibration, R.string.settings_pad_vibration_strength,
    R.string.settings_pad_vibration_test, R.string.settings_pad_vibration_fallback
)
