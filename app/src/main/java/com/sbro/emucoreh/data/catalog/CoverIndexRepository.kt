package com.sbro.emucoreh.data.catalog

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** A bundled, reviewed snapshot; regional product codes share a canonical IGDB game. */
class CoverIndexRepository(private val context: Context) {
    data class Cover(val url: String, val sourceUrl: String, val sha256: String)

    /**
     * Local serial -> catalogue id memory. Whenever a cover is resolved through
     * a title, the mapping is remembered for the disc's product code, so any
     * regional serial gets the same artwork even when the bundled index has no
     * entry for it yet.
     */
    private val learned by lazy {
        context.getSharedPreferences("cover_serial_map", Context.MODE_PRIVATE)
    }

    fun find(serial: String?, title: String?, threeDimensional: Boolean = false): Cover? {
        val index = load() ?: return null
        val titles = index.optJSONObject("titles")
        val normalizedSerial = serial.orEmpty().uppercase(Locale.ROOT).replace(Regex("[-_.\\s]"), "")
        val normalizedTitle = normalizeTitle(title.orEmpty())
        val learnedId = normalizedSerial.takeIf { it.isNotEmpty() }?.let { learned.getString(it, null) }
        var matchedByTitle = false
        val id = learnedId ?: index.optJSONObject("serials")?.optString(normalizedSerial).orEmpty().ifBlank {
            titles?.optString(normalizedTitle).orEmpty().ifBlank {
                fuzzyTitleMatch(titles, normalizedTitle)
            }
        }.ifBlank { arcadeGameId(index, title) }.also {
            matchedByTitle = learnedId == null && it.isNotEmpty() && normalizedSerial.length >= 6
        }
        val game = index.optJSONObject("games")?.optJSONObject(id) ?: return arcadeCover(index, title, threeDimensional)
        val path = game.optString(if (threeDimensional) "path_3d" else "path")
        if (!Regex("covers/(3d/)?[0-9]+\\.(jpg|png|webp)").matches(path)) {
            return arcadeCover(index, title, threeDimensional)
        }
        if (matchedByTitle) learned.edit().putString(normalizedSerial, id).apply()
        return Cover("$BASE_URL/$path", game.optString("source_url"), game.optString(if (threeDimensional) "sha256_3d" else "sha256"))
    }

    /** Romsets matched to an IGDB entry reuse the standard game artwork. */
    private fun arcadeGameId(index: JSONObject, title: String?): String =
        romsetKey(title)?.let { index.optJSONObject("arcade")?.optString(it).orEmpty() }.orEmpty()

    /**
     * Arcade archives (.zip/.7z/.dat/.lst) are identified by their romset name;
     * upstream covers exist for every romset Flycast supports.
     */
    private fun arcadeCover(index: JSONObject, title: String?, threeDimensional: Boolean): Cover? {
        val romset = romsetKey(title) ?: return null
        val entry = index.optJSONObject("arcadeFallback")?.optJSONObject(romset) ?: return null
        val path = entry.optString(if (threeDimensional) "path_3d" else "path")
        if (path.isBlank()) return null
        return Cover(
            "$BASE_URL/$path",
            "$BASE_URL/${entry.optString("path")}",
            entry.optString(if (threeDimensional) "sha256_3d" else "sha256"),
        )
    }

    private fun romsetKey(title: String?): String? {
        val key = title.orEmpty().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        return key.takeIf { it.length in 3..40 }
    }

    /**
     * Disc titles and IGDB names rarely match exactly ("SOUL REAVER" vs
     * "Legacy of Kain: Soul Reaver"), so a whole-phrase containment fallback
     * resolves the catalogue entry when the exact lookup misses.
     */
    private fun fuzzyTitleMatch(titles: JSONObject?, query: String): String {
        if (titles == null || query.length < 4) return ""
        val keys = titles.keys()
        var bestId = ""
        var bestLength = Int.MAX_VALUE
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.length < 4) continue
            val contains = key.contains(query) || query.contains(key)
            if (!contains) continue
            if (key.length < bestLength) {
                bestLength = key.length
                bestId = titles.optString(key)
            }
        }
        return bestId
    }

    private fun load(): JSONObject? = cached ?: synchronized(lock) {
        cached ?: runCatching {
            context.assets.open("catalog/dreamcast_covers.json").bufferedReader().use { JSONObject(it.readText()) }
        }.getOrNull()?.also { cached = it }
    }

    companion object {
        private const val BASE_URL = "https://raw.githubusercontent.com/sashkinbro/EmuCoreH-Covers/183c7701c36f8f40fb558e00718cbb8a6230c692"
        private val lock = Any()
        @Volatile private var cached: JSONObject? = null

        internal fun normalizeTitle(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[™®©]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
    }
}
