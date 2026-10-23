package com.sbro.emucoreh.data

import android.content.Context
import android.content.SharedPreferences
import com.sbro.emucoreh.core.EmulationPlatform
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject

data class SegaTouchLayoutProfile(
    val layout: TouchControlsLayoutProfile,
    val customControls: CustomTouchControlLibrary = CustomTouchControlLibrary.Empty
)

/** Separate preference namespace: creating/resetting a Sega layout never mutates Flycast layouts. */
class SegaTouchLayoutPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("sega_touch_layouts", Context.MODE_PRIVATE)

    fun load(platform: EmulationPlatform): SegaTouchLayoutProfile {
        require(platform.usesPlatformTouchLayouts)
        val json = preferences.getString(platform.name, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        return decode(platform, json)
    }

    fun save(platform: EmulationPlatform, layout: TouchControlsLayoutProfile, customControls: CustomTouchControlLibrary) {
        require(platform.usesPlatformTouchLayouts)
        check(preferences.edit().putString(platform.name, encode(platform, SegaTouchLayoutProfile(layout, customControls)).toString()).commit())
    }

    fun reset(platform: EmulationPlatform) {
        require(platform.usesPlatformTouchLayouts)
        check(preferences.edit().remove(platform.name).commit())
    }

    fun observe(platform: EmulationPlatform): Flow<SegaTouchLayoutProfile> = callbackFlow {
        require(platform.usesPlatformTouchLayouts)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == platform.name || key == null) trySend(load(platform))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(load(platform))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun exportJson(): JSONObject = JSONObject().apply {
        EmulationPlatform.entries.filter { it.usesPlatformTouchLayouts }.forEach { platform ->
            if (preferences.contains(platform.name)) put(platform.name, encode(platform, load(platform)))
        }
    }

    /** Missing key preserves installed layouts when restoring an older backup. */
    fun restoreJson(json: JSONObject?) {
        if (json == null) return
        val editor = preferences.edit()
        EmulationPlatform.entries.filter { it.usesPlatformTouchLayouts }.forEach { platform ->
            val value = json.optJSONObject(platform.name)
            if (value == null) editor.remove(platform.name)
            else editor.putString(platform.name, encode(platform, decode(platform, value)).toString())
        }
        check(editor.commit())
    }

    companion object {
        internal fun encode(platform: EmulationPlatform, profile: SegaTouchLayoutProfile): JSONObject = JSONObject()
            .put("layout", profile.layout.toJson())
            .put("customControls", SegaTouchLayouts.sanitizeCustomControls(platform, profile.customControls).encode())

        internal fun decode(platform: EmulationPlatform, json: JSONObject?): SegaTouchLayoutProfile = SegaTouchLayoutProfile(
            layout = json?.optJSONObject("layout")?.toTouchControlsLayoutProfile()
                ?: SegaTouchLayouts.defaultProfile(platform),
            customControls = SegaTouchLayouts.sanitizeCustomControls(platform, CustomTouchControlLibrary.decode(json?.optString("customControls")))
        )
    }
}
