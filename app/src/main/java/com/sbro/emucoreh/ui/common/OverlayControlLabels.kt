package com.sbro.emucoreh.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sbro.emucoreh.R

/** Hardware letters remain printed labels; system button names follow the app language. */
@Composable
fun localizedOverlayLabel(label: String): String = when (label) {
    "Mode" -> stringResource(R.string.sega_mode)
    "Pause" -> stringResource(R.string.sega_pause)
    "Start" -> stringResource(R.string.settings_gamepad_action_start)
    else -> label
}
