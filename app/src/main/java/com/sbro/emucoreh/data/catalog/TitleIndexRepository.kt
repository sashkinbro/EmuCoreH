package com.sbro.emucoreh.data.catalog

import android.content.Context
import java.util.Locale
import kotlinx.serialization.json.Json

/**
 * Offline product-code -> English title index generated from the IGDB metadata
 * for Dreamcast, Naomi, Naomi 2 and Atomiswave (see IGDB/build_dreamcast_catalog.py).
 */
class TitleIndexRepository(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    fun titleForSerial(serial: String?): String? {
        val key = normalizeSerial(serial) ?: return null
        return loadIndex()[key]
    }

    /**
     * Best-effort reverse lookup: recovers a product code from a game title when
     * the filename does not contain one. Used by the library so cover art can
     * still be resolved for conventionally named dumps.
     */
    fun serialForTitle(title: String?): String? {
        val key = normalizeTitle(title) ?: return null
        return loadReverseIndex()[key]?.firstOrNull()
    }

    private fun loadReverseIndex(): Map<String, List<String>> {
        cachedReverseIndex?.let { return it }
        synchronized(cacheLock) {
            cachedReverseIndex?.let { return it }
            val reverse = LinkedHashMap<String, MutableList<String>>()
            loadIndex().forEach { (serial, title) ->
                val key = normalizeTitle(title) ?: return@forEach
                reverse.getOrPut(key) { mutableListOf() }.add(serial)
            }
            cachedReverseIndex = reverse
            return reverse
        }
    }

    private fun normalizeTitle(title: String?): String? {
        val normalized = title
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("""\[[^]]*]|\([^)]*\)"""), " ")
            ?.replace(Regex("""\b(disc|disk|cd|gdi)\s*\d+\b"""), " ")
            ?.replace(Regex("""\b(usa|us|europe|eur|japan|jpn|asia|world|rev\s*[a-z0-9]+|beta|demo|proto)\b"""), " ")
            ?.replace(Regex("""[^a-z0-9]+"""), " ")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            .orEmpty()
        return normalized.takeIf { it.length >= 3 }
    }

    private fun loadIndex(): Map<String, String> {
        cachedIndex?.let { return it }
        synchronized(cacheLock) {
            cachedIndex?.let { return it }
            val parsed = runCatching {
                appContext.assets.open(ASSET_PATH).bufferedReader().use { reader ->
                    json.decodeFromString<Map<String, String>>(reader.readText())
                }
            }.getOrDefault(emptyMap())
            cachedIndex = parsed
            return parsed
        }
    }

    private fun normalizeSerial(serial: String?): String? {
        val normalized = serial
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.replace(Regex("[^A-Z0-9]"), "")
            .orEmpty()
        return normalized.takeIf { it.length >= 6 }
    }

    private companion object {
        private const val ASSET_PATH = "catalog/dreamcast_titles.json"
        private val cacheLock = Any()

        @Volatile
        private var cachedIndex: Map<String, String>? = null

        @Volatile
        private var cachedReverseIndex: Map<String, List<String>>? = null
    }
}
