package com.sbro.emucoreh.data.catalog

import android.content.Context
import com.sbro.emucoreh.data.AppPreferences
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * Universal cover catalog repository backed by GitHub repository:
 * https://github.com/sashkinbro/EmuCoreH-Covers
 *
 * Mappings:
 * - Product code / serial -> cover image URL
 * - Game title -> cover image URL
 * - Arcade romset / unique ID -> arcade cover image URL
 */
class CoverIndexRepository(private val context: Context) {

    data class Cover(val url: String, val sourceUrl: String = "", val sha256: String = "")

    private val learned by lazy {
        context.getSharedPreferences("cover_serial_map", Context.MODE_PRIVATE)
    }

    /**
     * Resolves a cover URL by product code / serial (e.g. MK-51037, T1202N, HDR-0053).
     */
    fun findCoverBySerial(
        serial: String?,
        style: Int = AppPreferences.COVER_ART_STYLE_3D,
        isLight: Boolean = false
    ): String? {
        if (style == AppPreferences.COVER_ART_STYLE_DISABLED) return null
        val norm = normalizeSerial(serial) ?: return null
        val learnedPath = learned.getString(norm, null)
        if (!learnedPath.isNullOrBlank()) {
            return makeCoverUrl(learnedPath, style, isLight)
        }
        val index = load() ?: return null
        val serials = index.optJSONObject("serials") ?: return null
        val path = serials.optString(norm).takeIf { it.isNotBlank() } ?: return null
        return makeCoverUrl(path, style, isLight)
    }

    /**
     * Resolves a cover URL by game title (exact match first, fuzzy containment second).
     */
    fun findCoverByTitle(
        title: String?,
        style: Int = AppPreferences.COVER_ART_STYLE_3D,
        isLight: Boolean = false,
        allowFuzzy: Boolean = true
    ): String? {
        if (style == AppPreferences.COVER_ART_STYLE_DISABLED) return null
        val index = load() ?: return null
        val titles = index.optJSONObject("titles") ?: return null
        val path = resolveTitlePath(titles, title, allowFuzzy) ?: return null
        return makeCoverUrl(path, style, isLight)
    }

    /**
     * Resolves an arcade cover URL by archive romset name (e.g. 18wheelr) or ROM unique ID.
     */
    fun findArcadeCover(
        nameOrPath: String?,
        uniqueId: String? = null,
        style: Int = AppPreferences.COVER_ART_STYLE_3D,
        isLight: Boolean = false
    ): String? {
        if (style == AppPreferences.COVER_ART_STYLE_DISABLED) return null
        val index = load() ?: return null
        val arcade = index.optJSONObject("arcade") ?: return null
        val romset = romsetKey(nameOrPath)
        if (romset != null) {
            val path = arcade.optString(romset).takeIf { it.isNotBlank() }
            if (path != null) return makeCoverUrl(path, style, isLight)
        }
        if (!uniqueId.isNullOrBlank()) {
            val uidKey = uniqueId.trim().lowercase(Locale.ROOT)
            val path = arcade.optString(uidKey).takeIf { it.isNotBlank() }
            if (path != null) return makeCoverUrl(path, style, isLight)
        }
        return null
    }

    /** Legacy method maintained for backward compatibility with IgdbCatalogRepository. */
    fun find(serial: String?, title: String?, threeDimensional: Boolean = false): Cover? {
        val style = if (threeDimensional) AppPreferences.COVER_ART_STYLE_3D else AppPreferences.COVER_ART_STYLE_FLAT
        val url = findCoverBySerial(serial, style)
            ?: findCoverByTitle(title, style)
            ?: findArcadeCover(title, serial, style)
            ?: return null
        return Cover(url, url, "")
    }

    /** Product code -> catalogue id from the bundled serial map plus learned aliases. */
    fun gameIdForSerial(serial: String?): Long? {
        val norm = normalizeSerial(serial) ?: return null
        val index = load() ?: return null
        val serials = index.optJSONObject("serials") ?: return null
        val path = learned.getString(norm, null)
            ?: serials.optString(norm).takeIf { it.isNotBlank() }
            ?: return null
        return path.substringAfterLast('/').substringBeforeLast('.').toLongOrNull()
    }

    fun recordLearnedSerial(serial: String?, pathOrTitle: String?) {
        val norm = normalizeSerial(serial) ?: return
        if (norm.length < 4) return
        val normTitle = normalizeTitle(pathOrTitle.orEmpty())
        val index = load() ?: return
        val path = index.optJSONObject("titles")?.optString(normTitle)?.takeIf { it.isNotBlank() } ?: return
        learned.edit().putString(norm, path).apply()
    }



    private fun load(): JSONObject? = cached ?: synchronized(lock) {
        cached ?: runCatching {
            val hasCoversIndex = runCatching {
                context.assets.open("catalog/covers_index.json").close()
                true
            }.getOrDefault(false)

            val assetPath = if (hasCoversIndex) "catalog/covers_index.json" else "catalog/dreamcast_covers.json"
            context.assets.open(assetPath).bufferedReader().use { JSONObject(it.readText()) }
        }.getOrNull()?.also { cached = it }
    }

    companion object {
        const val BASE_URL = "https://raw.githubusercontent.com/sashkinbro/EmuCoreH-Covers/main"
        private val lock = Any()
        @Volatile private var cached: JSONObject? = null

        internal fun resolveTitlePath(titles: JSONObject, title: String?, allowFuzzy: Boolean = true): String? =
            CoverTitleMatcher.resolve(titles, title, allowFuzzy)

        fun normalizeSerial(serial: String?): String? {
            val norm = serial.orEmpty().uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
            return norm.takeIf { it.length >= 4 }
        }

        fun normalizeTitle(value: String): String = Normalizer.normalize(
            value.replace(Regex("[™®©]"), "").lowercase(Locale.ROOT),
            Normalizer.Form.NFKD
        )
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

        fun romsetKey(nameOrPath: String?): String? = nameOrPath
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.substringBeforeLast('.')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }

        fun makeCoverUrl(
            relPath: String,
            style: Int = AppPreferences.COVER_ART_STYLE_3D,
            isLight: Boolean = false
        ): String {
            val clean = relPath.trim().trimStart('/')
            if (style == AppPreferences.COVER_ART_STYLE_3D) {
                val ext = ".webp"
                val stem = clean.substringAfterLast('/').substringBeforeLast('.')
                val variant = if (isLight) "3d/light" else "3d"
                if (clean.startsWith("arcade/")) {
                    return "$BASE_URL/arcade/$variant/$stem$ext"
                }
                if (clean.startsWith("covers/")) {
                    return "$BASE_URL/covers/$variant/$stem$ext"
                }
            }
            return "$BASE_URL/$clean"
        }
    }
}
