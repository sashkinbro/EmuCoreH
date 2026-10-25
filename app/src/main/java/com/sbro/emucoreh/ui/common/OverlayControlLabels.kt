package com.sbro.emucoreh.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sbro.emucoreh.R

/** Hardware letters and the Sega Mode legend remain printed labels; other system names follow the app language. */
@Composable
fun localizedOverlayLabel(label: String): String = when (label) {
    "Mode" -> "Mode"
    "Pause" -> stringResource(R.string.sega_pause)
    "Start" -> stringResource(R.string.settings_gamepad_action_start)
    "Coin" -> stringResource(R.string.settings_gamepad_action_coin)
    "Service" -> stringResource(R.string.settings_gamepad_action_service)
    "Test" -> stringResource(R.string.settings_gamepad_action_test)
    else -> label
}
