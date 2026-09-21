package com.sbro.emucoreh.ui.gamedb

import android.content.Context
import com.sbro.emucoreh.data.AppPreferences
import com.sbro.emucoreh.data.CoverArtRepository
import java.util.Locale

enum class GameDbSettingCategory {
    CORE_FIX,
    ROUND_MODE,
    CLAMP_MODE,
    SPEED_HACK,
    GRAPHICS_FIX
}

data class GameDbSettingItem(
    val category: GameDbSettingCategory,
    val key: String,
    val value: String,
    val note: String?,
    val controlledByGameFixesToggle: Boolean
) {
    val searchableText: String = listOfNotNull(key, value, note)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
}

data class GameDbCatalogEntry(
    val serial: String,
    val title: String,
    val region: String?,
    val coverUrl: String?,
    val settings: List<GameDbSettingItem>
) {
    val coreSettingCount: Int = settings.count { it.controlledByGameFixesToggle }
    val graphicsSettingCount: Int = settings.size - coreSettingCount
    val searchableText: String = buildString {
        append(serial.lowercase(Locale.ROOT))
        append(' ')
        append(title.lowercase(Locale.ROOT))
        region?.let {
            append(' ')
            append(it.lowercase(Locale.ROOT))
        }
        settings.forEach {
            append(' ')
            append(it.searchableText)
        }
    }
}

class GameDbCatalogRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = AppPreferences(appContext)
    private val covers = CoverArtRepository(appContext)

    fun loadEntries(): List<GameDbCatalogEntry> {
        cachedEntries?.let { return it }
        synchronized(cacheLock) {
            cachedEntries?.let { return it }
            val parsed = parseGameIndex()
            cachedEntries = parsed
            return parsed
        }
    }

    private fun parseGameIndex(): List<GameDbCatalogEntry> {
        val entries = ArrayList<GameDbCatalogEntry>(4_000)
        var serial: String? = null
        var name: String? = null
        var englishName: String? = null
        var region: String? = null
        var activeSection: String? = null
        val settings = ArrayList<GameDbSettingItem>(8)

        fun flush() {
            val currentSerial = serial ?: return
            if (settings.isNotEmpty()) {
                entries += GameDbCatalogEntry(
                    serial = currentSerial,
                    title = englishName ?: name ?: currentSerial,
                    region = region,
                    coverUrl = covers.buildPublicCoverUrl(currentSerial, title = englishName ?: name),
                    settings = settings.toList()
                )
            }
        }

        appContext.assets.open(GAME_INDEX_ASSET_PATH).bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                if (rawLine.isBlank() || rawLine.trimStart().startsWith('#')) return@forEach
                val indent = rawLine.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                val content = rawLine.trim()

                if (indent == 0 && content.endsWith(':')) {
                    flush()
                    serial = content.removeSuffix(":").trim().uppercase(Locale.ROOT)
                    name = null
                    englishName = null
                    region = null
                    activeSection = null
                    settings.clear()
                    return@forEach
                }

                if (serial == null) return@forEach
                if (indent == 2) {
                    val (withoutComment, _) = splitComment(content)
                    val key = withoutComment.substringBefore(':').trim()
                    val value = withoutComment.substringAfter(':', "").trim()
                    when (key) {
                        "name" -> name = parseScalar(value)
                        "name-en" -> englishName = parseScalar(value)
                        "region" -> region = parseScalar(value)
                    }
                    activeSection = key.takeIf { it in supportedSections }
                    return@forEach
                }

                if (indent < 4) return@forEach
                val section = activeSection ?: return@forEach
                val (withoutComment, note) = splitComment(content)
                when (section) {
                    "gameFixes" -> {
                        if (withoutComment.startsWith('-')) {
                            val fix = parseScalar(withoutComment.removePrefix("-").trim())
                            if (fix.isNotBlank()) {
                                settings += GameDbSettingItem(
                                    category = GameDbSettingCategory.CORE_FIX,
                                    key = fix,
                                    value = "Enabled",
                                    note = note,
                                    controlledByGameFixesToggle = true
                                )
                            }
                        }
                    }
                    else -> {
                        if (!withoutComment.contains(':')) return@forEach
                        val key = withoutComment.substringBefore(':').trim()
                        val value = parseScalar(withoutComment.substringAfter(':').trim())
                        if (key.isBlank() || value.isBlank()) return@forEach
                        settings += GameDbSettingItem(
                            category = section.toCategory(),
                            key = key,
                            value = value,
                            note = note,
                            controlledByGameFixesToggle = section != "gsHWFixes"
                        )
                    }
                }
            }
        }
        flush()

        return entries.sortedWith(
            compareBy<GameDbCatalogEntry> { it.title.lowercase(Locale.ROOT) }
                .thenBy { it.serial }
        )
    }

    private fun String.toCategory(): GameDbSettingCategory = when (this) {
        "roundModes" -> GameDbSettingCategory.ROUND_MODE
        "clampModes" -> GameDbSettingCategory.CLAMP_MODE
        "speedHacks" -> GameDbSettingCategory.SPEED_HACK
        "gsHWFixes" -> GameDbSettingCategory.GRAPHICS_FIX
        else -> GameDbSettingCategory.CORE_FIX
    }

    private fun parseScalar(raw: String): String {
        return raw.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .replace("\\\"", "\"")
    }

    private fun splitComment(line: String): Pair<String, String?> {
        var singleQuoted = false
        var doubleQuoted = false
        line.forEachIndexed { index, char ->
            when (char) {
                '\'' -> if (!doubleQuoted) singleQuoted = !singleQuoted
                '"' -> if (!singleQuoted && (index == 0 || line[index - 1] != '\\')) doubleQuoted = !doubleQuoted
                '#' -> if (!singleQuoted && !doubleQuoted) {
                    return line.substring(0, index).trimEnd() to
                        line.substring(index + 1).trim().takeIf { it.isNotBlank() }
                }
            }
        }
        return line.trimEnd() to null
    }

    private companion object {
        private const val GAME_INDEX_ASSET_PATH = "resources/GameIndex.yaml"
        private val supportedSections = setOf("gameFixes", "roundModes", "clampModes", "speedHacks", "gsHWFixes")
        private val cacheLock = Any()

        @Volatile
        private var cachedEntries: List<GameDbCatalogEntry>? = null
    }
}
