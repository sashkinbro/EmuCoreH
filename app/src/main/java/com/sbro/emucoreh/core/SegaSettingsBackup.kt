package com.sbro.emucoreh.core

import android.content.Context
import org.json.JSONObject

/** Optional nested settings preserve compatibility with existing backup formats. */
object SegaSettingsBackup {
    fun export(context: Context): JSONObject = JSONObject().apply {
        put("coreOptions", JSONObject().apply {
            EmulationPlatform.entries.filter { it.isSega }.forEach { platform ->
                put(platform.name, JSONObject(SegaCoreOptions.persistedEntries(context, platform)))
            }
        })
        put("touchLayouts", com.sbro.emucoreh.data.SegaTouchLayoutPreferences(context).exportJson())
        put("gamepadProfiles", JSONObject().apply {
            EmulationPlatform.entries.filter { it.usesPlatformTouchLayouts }.forEach { platform ->
                put(platform.name, JSONObject().apply {
                    SegaControlProfiles.load(context, platform).forEach { (pad, bindings) ->
                        put(pad.toString(), JSONObject(bindings))
                    }
                })
            }
        })
    }

    fun resetAll(context: Context) {
        SegaCoreOptions.resetAll(context)
        SegaControlProfiles.resetAll(context)
        com.sbro.emucoreh.data.SegaTouchLayoutPreferences(context).restoreJson(JSONObject())
    }

    fun restore(context: Context, json: JSONObject?) {
        if (json == null) return
        com.sbro.emucoreh.data.SegaTouchLayoutPreferences(context).restoreJson(json.optJSONObject("touchLayouts"))
        json.optJSONObject("coreOptions")?.let { options ->
            EmulationPlatform.entries.filter { it.isSega }.forEach { platform ->
                options.optJSONObject(platform.name)?.let { values ->
                    values.keys().forEach { key -> SegaCoreOptions.set(context, key, values.optString(key), platform) }
                }
            }
        }
        json.optJSONObject("gamepadProfiles")?.let { profiles ->
            EmulationPlatform.entries.filter { it.usesPlatformTouchLayouts }.forEach { platform ->
                profiles.optJSONObject(platform.name)?.let { profile ->
                    val bindings = profile.keys().asSequence().mapNotNull { padKey ->
                        val pad = padKey.toIntOrNull()?.takeIf { it in 0..3 } ?: return@mapNotNull null
                        val values = profile.optJSONObject(padKey) ?: return@mapNotNull null
                        pad to values.keys().asSequence().associateWith { values.optInt(it) }
                    }.toMap()
                    SegaControlProfiles.save(context, platform, bindings)
                }
            }
        }
    }
}
