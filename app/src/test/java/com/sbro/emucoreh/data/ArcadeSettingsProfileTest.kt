package com.sbro.emucoreh.data

import com.sbro.emucoreh.ui.settings.resolveAgainst
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcadeSettingsProfileTest {
    @Test
    fun olderProfilesInheritNewArcadeSettings() {
        val profile = JSONObject("""{"gameKey":"old","gameTitle":"Old","gyroMode":1}""").toPerGameSettings()
        val global = PerGameSettings(
            gameKey = "global", gameTitle = "Global", gyroStickTarget = 1,
            lightGunAimSource = 2, lightGunCursorEnabled = false,
            arcadeDevice1 = 2, arcadeDevice2 = 1
        )

        val resolved = profile.resolveAgainst(global)

        assertEquals(1, resolved.gyroMode)
        assertEquals(1, resolved.gyroStickTarget)
        assertEquals(2, resolved.lightGunAimSource)
        assertFalse(resolved.lightGunCursorEnabled)
        assertEquals(2, resolved.arcadeDevice1)
        assertEquals(1, resolved.arcadeDevice2)
        assertFalse(profile.toJson().has("lightGunAimSource"))
    }

    @Test
    fun explicitArcadeSettingsSurviveExportAndImport() {
        val profile = PerGameSettings(
            gameKey = "gun", gameTitle = "Gun", gyroMode = AppPreferences.GYRO_MODE_LIGHT_GUN,
            gyroStickTarget = 1, lightGunAimSource = AppPreferences.LIGHT_GUN_AIM_DPAD,
            lightGunCursorEnabled = false,
            arcadeDevice1 = 2, arcadeDevice2 = 1
        )

        val restored = profile.toJson().toPerGameSettings()

        assertEquals(3, restored.gyroMode)
        assertEquals(1, restored.gyroStickTarget)
        assertEquals(AppPreferences.LIGHT_GUN_AIM_DPAD, restored.lightGunAimSource)
        assertFalse(restored.lightGunCursorEnabled)
        assertEquals(2, restored.arcadeDevice1)
        assertEquals(1, restored.arcadeDevice2)
        assertTrue(restored.providedKeys!!.containsAll(setOf("gyroStickTarget", "lightGunAimSource", "lightGunCursorEnabled", "arcadeDevice1", "arcadeDevice2")))
    }

    @Test
    fun explicitAutomaticDeviceAndGyroAimOverrideGlobalChoices() {
        val profile = JSONObject("""{"gameKey":"gun","gameTitle":"Gun","arcadeDevice1":0,"lightGunAimSource":0,"lightGunCursorEnabled":true}""").toPerGameSettings()
        val global = PerGameSettings(
            gameKey = "global", gameTitle = "Global", gyroStickTarget = 1,
            lightGunAimSource = 2, lightGunCursorEnabled = false,
            arcadeDevice1 = 2, arcadeDevice2 = 1
        )

        val resolved = profile.resolveAgainst(global)

        assertEquals(0, resolved.arcadeDevice1)
        assertEquals(1, resolved.arcadeDevice2)
        assertEquals(0, resolved.lightGunAimSource)
        assertTrue(resolved.lightGunCursorEnabled)
        assertEquals(1, resolved.gyroStickTarget)
    }

    @Test
    fun malformedArcadeValuesAreSanitizedOnImport() {
        val profile = JSONObject("""{"gameKey":"gun","gameTitle":"Gun","gyroMode":99,"gyroStickTarget":99,"lightGunAimSource":-1,"arcadeDevice1":99,"arcadeDevice2":-1}""").toPerGameSettings()

        assertEquals(3, profile.gyroMode)
        assertEquals(1, profile.gyroStickTarget)
        assertEquals(0, profile.lightGunAimSource)
        assertEquals(2, profile.arcadeDevice1)
        assertEquals(0, profile.arcadeDevice2)
        assertTrue(profile.lightGunCursorEnabled)

        val clamped = JSONObject("""{"gameKey":"gun","gameTitle":"Gun","lightGunAimSource":99}""").toPerGameSettings()
        assertEquals(AppPreferences.LIGHT_GUN_AIM_MAX, clamped.lightGunAimSource)
    }

    @Test
    fun gunTouchButtonsAreAvailableButHiddenByDefault() {
        val defaults = AppPreferences.defaultOverlayControlLayouts()
        listOf("gun_trigger", "gun_pedal", "gun_reload", "gun_recalibrate").forEach {
            assertFalse("$it must be hidden", defaults.getValue(it).visible)
        }
    }
}
