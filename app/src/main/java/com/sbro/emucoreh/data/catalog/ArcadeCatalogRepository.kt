// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.data.catalog

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Frontend mirror of the vendored Flycast core's arcade game table (`Games[]`
 * in `core/core/hw/naomi/naomi_roms.cpp`).
 *
 * Flycast's standalone scanner only lists a `.zip`/`.7z` when its base name is
 * a known Naomi, Naomi 2, Atomiswave or System SP romset, and it hides raw
 * arcade GD-ROM images whose basename is part of such a set. Without this
 * table the library cannot tell a ROM set from an unrelated archive, so arcade
 * content either disappears from the library or is mislabelled.
 *
 * The asset is generated from the vendored core by
 * `tools/build_arcade_games_catalog.py`; regenerate it whenever the core is
 * updated so both sides agree.
 */
class ArcadeCatalogRepository(context: Context) {

    data class ArcadeGame(
        val romset: String,
        val title: String,
        val parent: String? = null,
        val bios: String? = null,
        val cartridge: String? = null,
        val rotation: String? = null,
        val gdrom: String? = null,
    ) {
        /** Vertical cabinets (for example Ikaruga) report ROT270. */
        val isVertical: Boolean get() = rotation.equals("ROT270", ignoreCase = true)

        /** Arcade titles booting from a GD-ROM need a companion `.chd` image. */
        val usesGdrom: Boolean get() = cartridge.equals("GD", ignoreCase = true)
    }

    private val appContext = context.applicationContext

    private val catalog: JSONObject?
        get() = cached ?: synchronized(lock) { cached ?: load()?.also { cached = it } }

    private fun load(): JSONObject? = runCatching {
        appContext.assets.open(ASSET_PATH).bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()

    /** Parsed `games` object keyed by lowercase romset. */
    private val games: JSONObject? by lazy { catalog?.optJSONObject("games") }

    private val gdroms: Set<String> by lazy {
        catalog?.optJSONArray("gdroms")?.let { array ->
            buildSet { for (index in 0 until array.length()) add(array.optString(index)) }
        }.orEmpty()
    }

    /** Resolves a file name, path or bare romset to its arcade game entry. */
    fun find(nameOrPath: String?): ArcadeGame? {
        val romset = romsetKey(nameOrPath) ?: return null
        val entry = games?.optJSONObject(romset) ?: return null
        return ArcadeGame(
            romset = romset,
            title = entry.optString("title").takeIf { it.isNotBlank() } ?: romset,
            parent = entry.optString("parent").takeIf { it.isNotBlank() },
            bios = entry.optString("bios").takeIf { it.isNotBlank() },
            cartridge = entry.optString("cart").takeIf { it.isNotBlank() },
            rotation = entry.optString("rotation").takeIf { it.isNotBlank() },
            gdrom = entry.optString("gdrom").takeIf { it.isNotBlank() },
        )
    }

    fun isKnownRomset(nameOrPath: String?): Boolean = find(nameOrPath) != null

    /**
     * True when the disc image belongs to an arcade GD-ROM set. Flycast boots
     * those through the romset archive, so the raw image is hidden from the
     * library just like the standalone scanner does.
     */
    fun isArcadeGdrom(nameOrPath: String?): Boolean {
        val key = romsetKey(nameOrPath) ?: return false
        return gdroms.contains(key)
    }

    companion object {
        private const val ASSET_PATH = "catalog/arcade_games.json"
        private val lock = Any()

        @Volatile
        private var cached: JSONObject? = null

        /** Lowercase base name without directory or extension. */
        internal fun romsetKey(nameOrPath: String?): String? = nameOrPath
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.substringBeforeLast('.')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
    }
}
