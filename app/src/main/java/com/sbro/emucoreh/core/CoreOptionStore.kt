// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted overrides for the Flycast libretro core options.
 *
 * The keys and accepted values are defined by [FlycastCoreOptions] and the
 * vendored core itself; this store only remembers what the user picked so the
 * options can be pushed again before every launch. The settings UI reads the
 * same values synchronously.
 */
object CoreOptionStore {
    private const val PREFS_NAME = "flycast_core_options"
    private var prefs: SharedPreferences? = null
    private val cache = HashMap<String, String>()

    fun value(key: String): String? = cache[key]

    /** All persisted core option overrides (curated + full catalogue). */
    fun persistedEntries(): Map<String, String> = HashMap(cache)

    fun set(key: String, value: String) {
        cache[key] = value
        prefs?.edit()?.putString(key, value)?.apply()
    }

    fun remove(key: String) {
        cache.remove(key)
        prefs?.edit()?.remove(key)?.apply()
    }

    fun initialize(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        cache.clear()
        prefs?.all?.forEach { (key, value) -> (value as? String)?.let { cache[key] = it } }
    }
}
