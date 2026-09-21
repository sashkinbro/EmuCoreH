package com.sbro.emucoreh.data

import android.content.Context
import com.sbro.emucoreh.core.BiosValidator
import com.sbro.emucoreh.core.DocumentPathResolver
import com.sbro.emucoreh.core.EmulatorBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class GameLibraryCacheSnapshot(
    val games: List<GameItem>,
    val savedAt: Long,
    val preferEnglishTitles: Boolean = false
)

class GameLibraryCacheRepository(context: Context) {

    companion object {
        private const val CACHE_SCHEMA_VERSION = 5

        fun libraryKey(paths: List<String>): String =
            paths.map(String::trim).filter(String::isNotBlank).distinct().joinToString("\u001F")
    }

    private val appContext = context.applicationContext
    private val cacheFile = File(appContext.filesDir, "library/game_library_cache.json")
    private val learnedSerials = LearnedSerialRepository.forContext(appContext)
    private val lock = Any()

    fun load(rootPath: String): List<GameItem> {
        return loadSnapshot(rootPath).games
    }

    fun loadSnapshot(rootPath: String, preferEnglishTitles: Boolean? = null): GameLibraryCacheSnapshot {
        synchronized(lock) {
            if (!cacheFile.exists()) return GameLibraryCacheSnapshot(emptyList(), 0L)

            return runCatching {
                val rootObject = JSONObject(cacheFile.readText())
                if (rootObject.optInt("schema_version", 0) != CACHE_SCHEMA_VERSION) {
                    return GameLibraryCacheSnapshot(emptyList(), 0L)
                }
                val libraries = rootObject.optJSONArray("libraries")
                    ?: return GameLibraryCacheSnapshot(emptyList(), 0L)
                val libraryObject = (0 until libraries.length())
                    .mapNotNull { index -> libraries.optJSONObject(index) }
                    .firstOrNull { it.optString("root_path") == rootPath }
                    ?: return GameLibraryCacheSnapshot(emptyList(), 0L)
                val cachedPreference = libraryObject.optBoolean("prefer_english_titles", false)
                if (preferEnglishTitles != null && cachedPreference != preferEnglishTitles) {
                    return GameLibraryCacheSnapshot(emptyList(), 0L, cachedPreference)
                }

                val games = libraryObject.optJSONArray("games") ?: JSONArray()
                val learned = learnedSerials.all()
                val parsedGames = buildList {
                    for (index in 0 until games.length()) {
                        val game = games.optJSONObject(index) ?: continue
                        val path = game.optString("path")
                        val fileName = sanitizeCachedFileName(path, game.optString("file_name"))
                        val serial = game.optString("serial").takeIf { it.isNotBlank() }
                            ?: learned[LearnedSerialRepository.key(fileName)]
                        val title = sanitizeCachedTitle(
                            path = path,
                            rawTitle = game.optString("title", fileName),
                            fileName = fileName
                        )
                        val fileSize = game.optLong("file_size")
                        if (BiosValidator.isLikelyBiosLibraryEntry(fileName, title, serial, fileSize)) {
                            continue
                        }
                        add(
                            GameItem(
                                title = title,
                                path = path,
                                fileName = fileName,
                                fileSize = fileSize,
                                lastModified = game.optLong("last_modified"),
                                coverArtPath = game.optString("cover_art_path").takeIf { it.isNotBlank() },
                                serial = serial,
                                discSize = game.optLong("disc_size").takeIf { it > 0L }
                            )
                        )
                    }
                }.distinctBy { GameRepository.libraryIdentity(it.path) }
                GameLibraryCacheSnapshot(
                    games = parsedGames,
                    savedAt = libraryObject.optLong("saved_at", 0L),
                    preferEnglishTitles = cachedPreference
                )
            }.getOrDefault(GameLibraryCacheSnapshot(emptyList(), 0L))
        }
    }

    fun save(rootPath: String, games: List<GameItem>, preferEnglishTitles: Boolean = false) {
        synchronized(lock) {
            runCatching {
                cacheFile.parentFile?.mkdirs()
                val rootObject = if (cacheFile.exists()) {
                    JSONObject(cacheFile.readText())
                } else {
                    JSONObject()
                }

                val existingLibraries = rootObject.optJSONArray("libraries") ?: JSONArray()
                val updatedLibraries = JSONArray()
                for (index in 0 until existingLibraries.length()) {
                    val libraryObject = existingLibraries.optJSONObject(index) ?: continue
                    if (libraryObject.optString("root_path") != rootPath) {
                        updatedLibraries.put(libraryObject)
                    }
                }

                updatedLibraries.put(
                    JSONObject().apply {
                        put("root_path", rootPath)
                        put("saved_at", System.currentTimeMillis())
                        put("prefer_english_titles", preferEnglishTitles)
                        put(
                            "games",
                            JSONArray().apply {
                                games.forEach { game ->
                                    put(
                                        JSONObject().apply {
                                            put("title", game.title)
                                            put("path", game.path)
                                            put("file_name", game.fileName)
                                            put("file_size", game.fileSize)
                                            put("disc_size", game.discSize ?: 0L)
                                            put("last_modified", game.lastModified)
                                            put("cover_art_path", game.coverArtPath ?: "")
                                            put("serial", game.serial ?: "")
                                        }
                                    )
                                }
                            }
                        )
                    }
                )

                rootObject.put("schema_version", CACHE_SCHEMA_VERSION)
                rootObject.put("libraries", updatedLibraries)
                cacheFile.writeText(rootObject.toString())
            }
        }
    }

    private fun sanitizeCachedFileName(path: String, rawFileName: String): String {
        val repaired = repairDisplayName(path, rawFileName)
        if (repaired.isNotBlank()) return repaired
        return repairDisplayName(path, path)
    }

    private fun sanitizeCachedTitle(path: String, rawTitle: String, fileName: String): String {
        return EmulatorBridge.cleanGameDisplayTitle(rawTitle, fileName.ifBlank { path })
    }

    private fun repairDisplayName(path: String, value: String): String {
        val source = when {
            needsDisplayRepair(value) || value.isBlank() -> path.ifBlank { value }
            else -> value
        }

        return if (source.startsWith("content://")) {
            runCatching { DocumentPathResolver.getDisplayName(appContext, source) }.getOrNull().orEmpty()
        } else {
            DocumentPathResolver.normalizeDisplayName(source)
        }
    }

    private fun needsDisplayRepair(value: String): Boolean {
        return value.contains("%2F", ignoreCase = true) ||
            value.contains("%3A", ignoreCase = true) ||
            value.startsWith("primary:", ignoreCase = true) ||
            value.startsWith("home:", ignoreCase = true) ||
            value.startsWith("raw:", ignoreCase = true)
    }
}
