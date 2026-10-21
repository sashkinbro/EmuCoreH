// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.data

private const val KEYBOARD_MOUSE_KEY = "keyboardMouse"

/** Updates only keyboard/mouse settings, retaining every other per-game override. */
fun PerGameSettings.withKeyboardMouseSettings(
    settings: KeyboardMouseSettings?
): PerGameSettings {
    val updatedKeys = providedKeys?.let { keys ->
        if (settings == null) keys - KEYBOARD_MOUSE_KEY else keys + KEYBOARD_MOUSE_KEY
    }
    return copy(keyboardMouse = settings, providedKeys = updatedKeys)
}
