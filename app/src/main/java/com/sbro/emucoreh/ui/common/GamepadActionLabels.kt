package com.sbro.emucoreh.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sbro.emucoreh.R
import com.sbro.emucoreh.core.GamepadManager
import com.sbro.emucoreh.core.EmulationPlatform

@StringRes
fun gamepadActionLabelRes(actionId: String): Int = when (actionId) {
    "cross" -> R.string.settings_gamepad_action_cross
    "circle" -> R.string.settings_gamepad_action_circle
    "square" -> R.string.settings_gamepad_action_square
    "triangle" -> R.string.settings_gamepad_action_triangle
    "l1" -> R.string.settings_gamepad_action_l1
    "r1" -> R.string.settings_gamepad_action_r1
    "l2" -> R.string.settings_gamepad_action_l2
    "r2" -> R.string.settings_gamepad_action_r2
    "l3" -> R.string.settings_gamepad_action_l3
    "r3" -> R.string.settings_gamepad_action_r3
    "select" -> R.string.settings_gamepad_action_select
    "pressure" -> R.string.settings_gamepad_action_pressure
    "gun_trigger" -> R.string.settings_gamepad_action_gun_trigger
    "gun_pedal" -> R.string.settings_gamepad_action_gun_pedal
    "gun_reload" -> R.string.settings_gamepad_action_gun_reload
    "gun_recalibrate" -> R.string.settings_gamepad_action_gun_recalibrate
    "coin" -> R.string.settings_gamepad_action_coin
    "test" -> R.string.settings_gamepad_action_test
    "service" -> R.string.settings_gamepad_action_service
    "start" -> R.string.settings_gamepad_action_start
    "left_input_toggle" -> R.string.settings_gamepad_action_left_input_toggle
    GamepadManager.ACTION_QUICK_SAVE -> R.string.emulation_quick_save
    GamepadManager.ACTION_QUICK_LOAD -> R.string.emulation_quick_load
    GamepadManager.ACTION_FAST_FORWARD -> R.string.emulation_fast_forward
    "dpad_up" -> R.string.settings_gamepad_action_dpad_up
    "dpad_down" -> R.string.settings_gamepad_action_dpad_down
    "dpad_left" -> R.string.settings_gamepad_action_dpad_left
    "dpad_right" -> R.string.settings_gamepad_action_dpad_right
    "left_stick_up" -> R.string.settings_gamepad_action_left_stick_up
    "left_stick_down" -> R.string.settings_gamepad_action_left_stick_down
    "left_stick_left" -> R.string.settings_gamepad_action_left_stick_left
    "left_stick_right" -> R.string.settings_gamepad_action_left_stick_right
    "right_stick_up" -> R.string.settings_gamepad_action_right_stick_up
    "right_stick_down" -> R.string.settings_gamepad_action_right_stick_down
    "right_stick_left" -> R.string.settings_gamepad_action_right_stick_left
    "right_stick_right" -> R.string.settings_gamepad_action_right_stick_right
    else -> R.string.settings_gamepad_section
}

@Composable
fun gamepadActionLabel(actionId: String, arcade: Boolean = false, platform: EmulationPlatform = EmulationPlatform.DREAMCAST): String {
    if (platform.isModel3) return when (actionId) {
        "cross" -> "A"; "circle" -> "B"; "square" -> "X"; "triangle" -> "Y"
        "l1" -> "L"; "r1" -> "R"
        "select" -> stringResource(R.string.settings_gamepad_action_coin)
        "l3" -> stringResource(R.string.settings_gamepad_action_service)
        "r3" -> stringResource(R.string.settings_gamepad_action_test)
        else -> stringResource(gamepadActionLabelRes(actionId))
    }
    if (platform.isSega) {
        val twoButtons = platform in setOf(EmulationPlatform.MASTER_SYSTEM, EmulationPlatform.GAME_GEAR, EmulationPlatform.SG_1000)
        return when (actionId) {
            "cross" -> if (twoButtons) "1" else "B"
            "circle" -> if (twoButtons) "2" else "C"
            "square" -> "A"
            "triangle" -> "Y"
            "l1" -> "X"
            "r1" -> "Z"
            "select" -> stringResource(R.string.sega_mode)
            "start" -> if (platform == EmulationPlatform.MASTER_SYSTEM || platform == EmulationPlatform.SG_1000)
                stringResource(R.string.sega_pause) else stringResource(R.string.settings_gamepad_action_start)
            else -> stringResource(gamepadActionLabelRes(actionId))
        }
    }
    if (arcade) {
        // Flycast numbers the arcade buttons 1..8; the ids keep their legacy
        // PlayStation values, the core maps them to Naomi/Atomiswave buttons.
        val arcadeButton = when (actionId) {
            "cross" -> 1
            "circle" -> 2
            "square" -> 3
            "triangle" -> 4
            "r1" -> 5
            "l1" -> 6
            "r2" -> 7
            "l2" -> 8
            else -> 0
        }
        if (arcadeButton > 0) {
            return stringResource(R.string.settings_gamepad_action_arcade_button, arcadeButton)
        }
    }
    return when (actionId) {
        // Dreamcast face button names; the ids keep their legacy PlayStation
        // values for compatibility with existing mappings and layouts.
        "cross" -> "A"
        "circle" -> "B"
        "square" -> "X"
        "triangle" -> "Y"
        else -> stringResource(gamepadActionLabelRes(actionId))
    }
}

fun gamepadActionSupported(actionId: String, platform: EmulationPlatform): Boolean =
    com.sbro.emucoreh.core.SegaControlProfiles.supports(platform, actionId)
