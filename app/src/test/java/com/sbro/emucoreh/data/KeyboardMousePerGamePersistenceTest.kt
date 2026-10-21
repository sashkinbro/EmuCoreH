// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardMousePerGamePersistenceTest {
    @Test
    fun keyboardMouseOverrideAddsAndRemovesItsSelectiveKey() {
        val profile = PerGameSettings(
            gameKey = "game.gdi",
            gameTitle = "Dreamcast game",
            renderer = 14,
            providedKeys = setOf("renderer")
        )
        val settings = KeyboardMouseSettings(
            keyboardEnabled = true,
            mouseEnabled = true,
            keyboardBindingsByPad = mapOf(0 to mapOf("cross" to 39)),
            mouseBindingsByPad = mapOf(1 to mapOf("circle" to 1))
        )

        val added = profile.withKeyboardMouseSettings(settings)
        val cleared = added.withKeyboardMouseSettings(null)

        assertEquals(settings, added.keyboardMouse)
        assertEquals(setOf("renderer", "keyboardMouse"), added.providedKeys)
        assertNull(cleared.keyboardMouse)
        assertEquals(setOf("renderer"), cleared.providedKeys)
        assertEquals(14, cleared.renderer)
    }

    @Test
    fun oldFullProfilesKeepFullProfileSemanticsAfterKeyboardMouseChange() {
        val oldProfile = PerGameSettings(
            gameKey = "old.gdi",
            gameTitle = "Old game",
            providedKeys = null
        )

        assertNull(oldProfile.withKeyboardMouseSettings(KeyboardMouseSettings(keyboardEnabled = true)).providedKeys)
        assertNull(oldProfile.withKeyboardMouseSettings(null).providedKeys)
    }

    @Test
    fun keyboardMouseSettingsRoundTripThroughThePerGameJsonCodec() {
        val settings = KeyboardMouseSettings(
            keyboardTargetPad = 1,
            mouseAimMode = AppPreferences.MOUSE_AIM_RIGHT_STICK,
            mouseSensitivity = 175,
            mouseWheelMode = AppPreferences.MOUSE_WHEEL_TRIGGERS,
            keyboardBindingsByPad = mapOf(1 to mapOf("start" to 66)),
            mouseBindingsByPad = mapOf(0 to mapOf("square" to 2))
        )
        val profile = PerGameSettings(gameKey = "codec.gdi", gameTitle = "Codec")
            .withKeyboardMouseSettings(settings)

        val decoded = profile.toJson().toPerGameSettings()

        assertEquals(settings, decoded.keyboardMouse)
        assertTrue(decoded.providedKeys?.contains("keyboardMouse") == true)
    }
}
