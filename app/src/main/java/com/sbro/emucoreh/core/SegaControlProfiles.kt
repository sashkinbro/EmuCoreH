package com.sbro.emucoreh.core

import android.content.Context
import com.sbro.emucoreh.data.GamepadBindingRules
import org.json.JSONObject

object SegaControlProfiles {
    fun supports(platform: EmulationPlatform, action: String): Boolean {
        if (!platform.isSega) return true
        if (action in setOf("cross", "circle", "dpad_up", "dpad_down", "dpad_left", "dpad_right", "quick_save", "quick_load", "fast_forward")) return true
        if (action == "start") return platform != EmulationPlatform.SG_1000
        return platform in setOf(EmulationPlatform.GENESIS, EmulationPlatform.SEGA_CD, EmulationPlatform.SEGA_32X) &&
            action in setOf("square", "triangle", "l1", "r1", "select")
    }

    /** Independent defaults for every hardware profile, using Android physical button codes. */
    fun defaults(platform: EmulationPlatform): Map<String, Int> = if (!platform.isSega) emptyMap() else
        linkedMapOf("cross" to 96, "circle" to 97, "square" to 99, "triangle" to 100,
            "l1" to 102, "r1" to 103, "select" to 109, "start" to 108,
            "dpad_up" to 19, "dpad_down" to 20, "dpad_left" to 21, "dpad_right" to 22)
            .filterKeys { supports(platform, it) }

    fun resolve(platform: EmulationPlatform, overrides: Map<Int, Map<String, Int>>): Map<Int, Map<String, Int>> =
        GamepadBindingRules.merge((0..3).associateWith { defaults(platform) },
            overrides.mapValues { (_, bindings) -> bindings.filterKeys { supports(platform, it) } })

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences("sega_gamepad_profiles", Context.MODE_PRIVATE)
    fun load(context: Context, platform: EmulationPlatform): Map<Int, Map<String, Int>> = runCatching {
        val json = JSONObject(preferences(context).getString(platform.name, "{}") ?: "{}")
        json.keys().asSequence().mapNotNull { index ->
            val pad = index.toIntOrNull()?.takeIf { it in 0..3 } ?: return@mapNotNull null
            val values = json.getJSONObject(index)
            pad to values.keys().asSequence().filter { supports(platform, it) }.associateWith { values.getInt(it) }
        }.toMap()
    }.getOrDefault(emptyMap())

    fun resetAll(context: Context) {
        check(preferences(context).edit().clear().commit())
        if (CoreRuntime.platform.isSega) GamepadManager.refreshSegaProfile(CoreRuntime.platform)
    }

    fun save(context: Context, platform: EmulationPlatform, overrides: Map<Int, Map<String, Int>>) {
        if (!platform.isSega) return
        val json = JSONObject()
        overrides.forEach { (pad, bindings) -> json.put(pad.toString(), JSONObject(bindings.filterKeys { supports(platform, it) })) }
        preferences(context).edit().putString(platform.name, json.toString()).apply()
        GamepadManager.refreshSegaProfile(platform)
    }
}


// Android D-pad key codes; merge the hat and processed left stick before emitting releases.
internal fun segaDpadDirections(leftX: Float, leftY: Float, hatX: Float, hatY: Float): Set<Int> = buildSet {
    if (leftX < -0.5f || hatX < -0.5f) add(21)
    if (leftX > 0.5f || hatX > 0.5f) add(22)
    if (leftY < -0.5f || hatY < -0.5f) add(19)
    if (leftY > 0.5f || hatY > 0.5f) add(20)
}
