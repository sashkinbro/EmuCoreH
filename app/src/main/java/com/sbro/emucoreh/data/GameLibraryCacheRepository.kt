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
        // v11: folder-title/platform hints and hidden GDI/CUE tracks; older
        // displayable snapshots refresh in the background instead of keeping
        // track files and shared `disc.gdi` titles in the library.
        private const val CACHE_SCHEMA_VERSION = 11

        fun libraryKey(paths: List<String>): String =
            paths.map(String::trim).filter(String::isNotBlank).distinct().joinToString("\u001F")
    }

    private val appContext = context.applicationContext
    private val cacheFile = File(appContext.filesDir, "library/game_library_cache.json")
    private val learnedSerials = LearnedSerialRepository.forContext(appContext)
    private val customCovers = CustomGameCoverRepository(appContext)
    private val lock = Any()

    fun load(rootPath: String): List<GameItem> {
        return loadSnapshot(rootPath).games
    }

    fun loadSnapshot(rootPath: String, preferEnglishTitles: Boolean? = null): GameLibraryCacheSnapshot {
        synchronized(lock) {
            if (!cacheFile.exists()) return GameLibraryCacheSnapshot(emptyList(), 0L)

            return runCatching {
                val rootObject = JSONObject(cacheFile.readText())
                val schema = rootObject.optInt("schema_version", 0)
                if (schema !in 8..CACHE_SCHEMA_VERSION) {
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
                        val decoded = game.readCachedGameItem() ?: continue
                        val path = decoded.path
                        val fileName = sanitizeCachedFileName(path, decoded.fileName)
                        val serial = decoded.serial ?: learned[LearnedSerialRepository.key(fileName)]
                        val title = sanitizeCachedTitle(
                            path = path,
                            rawTitle = game.optString("title", fileName),
                            fileName = fileName
                        )
                        if (BiosValidator.isLikelyBiosLibraryEntry(fileName, title, serial)) {
                            continue
                        }
                        add(
                            decoded.copy(
                                title = title,
                                fileName = fileName,
                                coverArtPath = decoded.coverArtPath?.takeUnless(customCovers::isMissingCustomCover),
                                serial = serial,
                                discs = decoded.discs.map { disc ->
                                    val discName = sanitizeCachedFileName(disc.path, disc.fileName)
                                    disc.copy(
                                        fileName = discName,
                                        serial = disc.serial ?: learned[LearnedSerialRepository.key(discName)]
                                    )
                                }
                            )
                        )
                    }
                }.distinctBy { GameRepository.libraryIdentity(it.path) }
                GameLibraryCacheSnapshot(
                    games = parsedGames,
                    savedAt = if (schema < CACHE_SCHEMA_VERSION) 0L else libraryObject.optLong("saved_at", 0L),
                    preferEnglishTitles = cachedPreference
                )
            }.getOrDefault(GameLibraryCacheSnapshot(emptyList(), 0L))
        }
    }

    /**
     * Returns the artwork already resolved for this exact library path, so the
     * in-game menu shows the same cover as the library even when the running
     * core reports a different display title than the cached catalog title.
     */
    fun findGameCoverPath(path: String): String? {
        if (path.isBlank() || !cacheFile.exists()) return null
        synchronized(lock) {
            return runCatching {
                val rootObject = JSONObject(cacheFile.readText())
                if (rootObject.optInt("schema_version", 0) !in 8..CACHE_SCHEMA_VERSION) return null
                val libraries = rootObject.optJSONArray("libraries") ?: return null
                for (libraryIndex in 0 until libraries.length()) {
                    val games = libraries.optJSONObject(libraryIndex)?.optJSONArray("games") ?: continue
                    for (gameIndex in 0 until games.length()) {
                        val game = games.optJSONObject(gameIndex) ?: continue
                        if (game.optString("path") != path) continue
                        return game.optString("cover_art_path").takeIf { coverPath ->
                            coverPath.isNotBlank() && (
                                coverPath.startsWith("content://") || coverPath.startsWith("http://") ||
                                    coverPath.startsWith("https://") || File(coverPath).isFile
                                )
                        }
                    }
                }
                null
            }.getOrNull()
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

                val existingLibraries = if (rootObject.optInt("schema_version") == CACHE_SCHEMA_VERSION) {
                    rootObject.optJSONArray("libraries") ?: JSONArray()
                } else JSONArray()
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
                                    put(game.toLibraryCacheJson())
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

/** Cache codecs stay independent of Android document access for round-trip tests. */
internal fun GameItem.toLibraryCacheJson(): JSONObject = JSONObject().apply {
    put("title", title)
    put("path", path)
    put("file_name", fileName)
    put("file_size", fileSize)
    put("disc_size", discSize ?: 0L)
    put("last_modified", lastModified)
    put("cover_art_path", coverArtPath ?: "")
    put("serial", serial ?: "")
    put("platform", platform ?: "")
    put("discs", JSONArray().apply {
        discs.forEach { disc ->
            put(JSONObject().apply {
                put("path", disc.path)
                put("file_name", disc.fileName)
                put("number", disc.number)
                put("serial", disc.serial ?: "")
                put("file_size", disc.fileSize)
                put("last_modified", disc.lastModified)
                put("disc_size", disc.discSize ?: 0L)
            })
        }
    })
}

internal fun JSONObject.readCachedGameItem(): GameItem? {
    val path = optString("path").takeIf(String::isNotBlank) ?: return null
    val name = optString("file_name").takeIf(String::isNotBlank) ?: return null
    val array = optJSONArray("discs")
    val members = if (array != null) {
        (0 until array.length()).map { index ->
            val disc = array.optJSONObject(index)
            val discPath = disc?.optString("path")?.takeIf(String::isNotBlank)
            val discName = disc?.optString("file_name")?.takeIf(String::isNotBlank)
            val number = disc?.optInt("number", 0)?.takeIf { it > 0 }
            if (disc == null || discPath == null || discName == null || number == null) null else GameDisc(
                path = discPath,
                fileName = discName,
                number = number,
                serial = disc.optString("serial").takeIf(String::isNotBlank),
                fileSize = disc.optLong("file_size"),
                lastModified = disc.optLong("last_modified"),
                discSize = disc.optLong("disc_size").takeIf { it > 0L }
            )
        }.takeIf { members ->
            members.size >= 2 && members.none { it == null } &&
                members.map { it?.path }.distinct().size == members.size &&
                members.map { it?.number }.distinct().size == members.size
        }?.filterNotNull().orEmpty()
    } else emptyList()
    return GameItem(
        title = optString("title", name), path = path, fileName = name,
        fileSize = optLong("file_size"), lastModified = optLong("last_modified"),
        coverArtPath = optString("cover_art_path").takeIf(String::isNotBlank),
        serial = optString("serial").takeIf(String::isNotBlank),
        discSize = optLong("disc_size").takeIf { it > 0L }, discs = members,
        platform = optString("platform").takeIf(String::isNotBlank)
    )
}
