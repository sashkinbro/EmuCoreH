package com.sbro.emucoreh.data.ps1

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import java.util.Locale

class Ps1CatalogRepository(private val context: Context) {

    companion object {
        private const val TAG = "Ps1CatalogRepository"
        // Keep the platform in the extracted filename so the old PS1 cache
        // cannot be mistaken for the bundled PSP database.
        private const val DB_NAME = "psp_games.db"
        private const val ASSET_PATH = "catalog/games.db"
    }

    private val dbFile: File by lazy { File(context.noBackupFilesDir, DB_NAME) }
    private var database: SQLiteDatabase? = null

    private val defaultSortOrder = """
        CASE WHEN rating IS NULL THEN 1 ELSE 0 END,
        rating DESC,
        CASE WHEN year IS NULL THEN 1 ELSE 0 END,
        year DESC,
        name COLLATE NOCASE ASC
    """.trimIndent()

    fun ensureDatabaseReady(): Boolean {
        if (dbFile.exists() && dbFile.length() > 0L) {
            return true
        }

        return runCatching {
            dbFile.parentFile?.mkdirs()
            context.assets.open(ASSET_PATH).use { input ->
                FileOutputStream(dbFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        }.getOrElse {
            Log.w(TAG, "Catalog DB is not bundled yet: ${it.message}")
            false
        }
    }

    fun hasCatalog(): Boolean = ensureDatabaseReady()

    fun search(query: String, limit: Int = 60, offset: Int = 0): List<Ps1CatalogSummary> {
        if (!ensureDatabaseReady()) return emptyList()
        return search(
            query = query,
            genre = null,
            year = null,
            minRating = null,
            limit = limit,
            offset = offset
        )
    }

    fun search(
        query: String,
        genre: String?,
        year: Int?,
        minRating: Double?,
        limit: Int = 60,
        offset: Int = 0
    ): List<Ps1CatalogSummary> {
        if (!ensureDatabaseReady()) return emptyList()
        val normalized = normalizeSearchText(query)
        if (normalized.isBlank()) {
            return topRated(
                genre = genre,
                year = year,
                minRating = minRating,
                limit = limit,
                offset = offset
            )
        }

        val db = getDatabase() ?: return emptyList()
        val out = ArrayList<Ps1CatalogSummary>(limit)
        val seen = HashSet<Long>(limit * 2)
        val fetchWindow = limit + offset
        querySearchPage(
            db = db,
            normalizedPattern = "$normalized%",
            genre = genre,
            year = year,
            minRating = minRating,
            limit = fetchWindow
        ).forEach { summary ->
            if (seen.add(summary.igdbId)) {
                out += summary
            }
        }

        if (out.size < fetchWindow) {
            querySearchPage(
                db = db,
                normalizedPattern = "%$normalized%",
                genre = genre,
                year = year,
                minRating = minRating,
                limit = fetchWindow
            ).forEach { summary ->
                if (seen.add(summary.igdbId)) {
                    out += summary
                }
            }
        }

        return out.drop(offset).take(limit)
    }

    fun getDetails(igdbId: Long): Ps1CatalogDetails? {
        if (!ensureDatabaseReady()) return null
        val db = getDatabase() ?: return null
        return db.rawQuery(
            """
            SELECT igdb_id, name, normalized_name, year, rating, summary, storyline, cover_url, hero_url
            FROM games
            WHERE igdb_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(igdbId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            loadDetails(db, cursorToSummary(cursor, db))
        }
    }

    fun findBestMatchId(serial: String?, title: String?): Long? {
        if (!ensureDatabaseReady()) return null
        val candidateSerials = listOfNotNull(serial)
            .map { it.normalizeSerialForMatch() }
            .filter { it.isNotBlank() }
            .distinct()

        val db = getDatabase() ?: return null
        findIdByDatabaseSerial(db, candidateSerials)?.let { id ->
            Log.d(TAG, "Catalog match by database serial: $serial -> $id")
            return id
        }

        val candidateTitles = listOfNotNull(title)
            .map(::normalizeIdentityTitle)
            .filter { it.isNotBlank() }
            .distinct()
        findExactTitleId(db, candidateTitles)?.let { id ->
            Log.d(TAG, "Catalog match by exact title: $title -> $id")
            return id
        }
        findFuzzyTitleId(db, candidateTitles)?.let { id ->
            Log.d(TAG, "Catalog match by fuzzy title: $title -> $id")
            return id
        }

        Log.w(TAG, "No catalog match for serial=$serial title=$title")
        return null
    }

    private fun findIdByDatabaseSerial(db: SQLiteDatabase, serials: List<String>): Long? {
        if (serials.isEmpty()) return null
        return runCatching {
            val placeholders = serials.joinToString(",") { "?" }
            db.rawQuery(
                """
                SELECT igdb_id
                FROM game_serials
                WHERE UPPER(REPLACE(REPLACE(REPLACE(REPLACE(serial, '-', ''), '_', ''), '.', ''), ' ', ''))
                    IN ($placeholders)
                LIMIT 1
                """.trimIndent(),
                serials.toTypedArray()
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        }.getOrNull()
    }

    private fun findExactTitleId(db: SQLiteDatabase, titles: List<String>): Long? {
        titles.forEach { title ->
            db.rawQuery(
                "SELECT igdb_id FROM games WHERE normalized_name = ? LIMIT 1",
                arrayOf(title)
            ).use { cursor ->
                if (cursor.moveToFirst()) return cursor.getLong(0)
            }
        }
        return null
    }

    private fun findFuzzyTitleId(db: SQLiteDatabase, titles: List<String>): Long? {
        if (titles.isEmpty()) return null
        var bestId: Long? = null
        var bestScore = 0.0
        db.rawQuery("SELECT igdb_id, normalized_name FROM games", null).use { cursor ->
            while (cursor.moveToNext()) {
                val catalogTitle = cursor.getString(1)
                titles.forEach { candidateTitle ->
                    val score = catalogTitleSimilarity(candidateTitle, catalogTitle)
                    if (score > bestScore) {
                        bestScore = score
                        bestId = cursor.getLong(0)
                    }
                }
            }
        }
        return bestId?.takeIf { bestScore >= MIN_FUZZY_TITLE_SCORE }
    }

    fun topRated(
        genre: String?,
        year: Int?,
        minRating: Double?,
        limit: Int = 60,
        offset: Int = 0
    ): List<Ps1CatalogSummary> {
        if (!ensureDatabaseReady()) return emptyList()
        val db = getDatabase() ?: return emptyList()
        val items = ArrayList<Ps1CatalogSummary>(limit)
        buildCatalogQuery(
            namePattern = null,
            genre = genre,
            year = year,
            minRating = minRating,
            includeOffset = true
        ).let { (sql, args) ->
            db.rawQuery(sql, (args + limit.toString() + offset.toString()).toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    items += cursorToSummary(cursor, db)
                }
            }
        }
        return items
    }

    fun getAvailableGenres(limit: Int = 18): List<String> {
        if (!ensureDatabaseReady()) return emptyList()
        val db = getDatabase() ?: return emptyList()
        val items = ArrayList<String>(limit)
        db.rawQuery(
            """
            SELECT genre_name, COUNT(*) AS usage_count
            FROM game_genres
            GROUP BY genre_name
            ORDER BY usage_count DESC, genre_name COLLATE NOCASE ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items += cursor.getString(0)
            }
        }
        return items
    }

    fun getAvailableYears(limit: Int = 30): List<Int> {
        if (!ensureDatabaseReady()) return emptyList()
        val db = getDatabase() ?: return emptyList()
        val items = ArrayList<Int>(limit)
        db.rawQuery(
            """
            SELECT DISTINCT year
            FROM games
            WHERE year IS NOT NULL
            ORDER BY year DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items += cursor.getInt(0)
            }
        }
        return items
    }

    private fun getDatabase(): SQLiteDatabase? {
        val current = database
        if (current != null && current.isOpen) {
            return current
        }

        return try {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).also { database = it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open catalog database: ${e.message}")
            null
        }
    }

    fun close() {
        database?.close()
        database = null
    }

    private fun querySearchPage(
        db: SQLiteDatabase,
        normalizedPattern: String,
        genre: String?,
        year: Int?,
        minRating: Double?,
        limit: Int
    ): List<Ps1CatalogSummary> {
        val out = ArrayList<Ps1CatalogSummary>(limit)
        buildCatalogQuery(
            namePattern = normalizedPattern,
            genre = genre,
            year = year,
            minRating = minRating,
            includeOffset = false
        ).let { (sql, args) ->
            db.rawQuery(sql, (args + limit.toString()).toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    out += cursorToSummary(cursor, db)
                }
            }
        }
        return out
    }

    private fun buildCatalogQuery(
        namePattern: String?,
        genre: String?,
        year: Int?,
        minRating: Double?,
        includeOffset: Boolean
    ): Pair<String, List<String>> {
        val where = ArrayList<String>(4)
        val args = ArrayList<String>(4)

        if (!namePattern.isNullOrBlank()) {
            where += "normalized_name LIKE ?"
            args += namePattern
        }
        if (!genre.isNullOrBlank()) {
            where += """
                EXISTS (
                    SELECT 1
                    FROM game_genres gg
                    WHERE gg.igdb_id = games.igdb_id
                      AND gg.genre_name = ?
                )
            """.trimIndent()
            args += genre
        }
        year?.let {
            where += "year = ?"
            args += it.toString()
        }
        minRating?.let {
            where += "rating >= ?"
            args += it.toString()
        }

        val whereClause = if (where.isEmpty()) "" else "WHERE ${where.joinToString(" AND ")}"
        val limitClause = if (includeOffset) "LIMIT ? OFFSET ?" else "LIMIT ?"
        return """
            SELECT igdb_id, name, normalized_name, year, rating, summary, storyline, cover_url, hero_url
            FROM games
            $whereClause
            ORDER BY $defaultSortOrder
            $limitClause
        """.trimIndent() to args
    }

    private fun loadDetails(db: SQLiteDatabase, summary: Ps1CatalogSummary): Ps1CatalogDetails {
        val genres = ArrayList<String>(summary.genres)
        if (genres.isEmpty()) {
            db.rawQuery(
                """
                SELECT genre_name
                FROM game_genres
                WHERE igdb_id = ?
                ORDER BY genre_name COLLATE NOCASE ASC
                """.trimIndent(),
                arrayOf(summary.igdbId.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    genres += cursor.getString(0)
                }
            }
        }

        val screenshots = ArrayList<String>(10)
        db.rawQuery(
            """
            SELECT image_url
            FROM game_screenshots
            WHERE igdb_id = ?
            ORDER BY position ASC
            LIMIT 10
            """.trimIndent(),
            arrayOf(summary.igdbId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                toHighResImageUrl(cursor.getString(0))?.let { screenshots += it }
            }
        }

        val videos = ArrayList<String>(10)
        db.rawQuery(
            """
            SELECT youtube_id
            FROM game_videos
            WHERE igdb_id = ?
            ORDER BY position ASC
            LIMIT 10
            """.trimIndent(),
            arrayOf(summary.igdbId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                videos += cursor.getString(0)
            }
        }

        return Ps1CatalogDetails(
            igdbId = summary.igdbId,
            name = summary.name,
            normalizedName = summary.normalizedName,
            year = summary.year,
            rating = summary.rating,
            storyline = summary.storyline,
            summary = summary.summary,
            genres = genres.distinct(),
            screenshots = screenshots,
            videos = videos,
            coverUrl = toHighResImageUrl(summary.coverUrl),
            heroUrl = toHighResImageUrl(summary.heroUrl),
            primarySerial = summary.primarySerial
        )
    }

    private fun cursorToSummary(cursor: android.database.Cursor, db: SQLiteDatabase): Ps1CatalogSummary {
        val igdbId = cursor.getLong(0)
        val name = cursor.getString(1)
        return Ps1CatalogSummary(
            igdbId = igdbId,
            name = name,
            normalizedName = cursor.getString(2),
            year = cursor.getIntOrNull(3),
            rating = cursor.getDoubleOrNull(4),
            summary = cursor.getStringOrNull(5),
            storyline = cursor.getStringOrNull(6),
            coverUrl = cursor.getStringOrNull(7),
            heroUrl = cursor.getStringOrNull(8),
            genres = loadGenresPreview(db, igdbId),
            primarySerial = loadPrimarySerial(db, igdbId)
        )
    }

    private fun loadPrimarySerial(db: SQLiteDatabase, igdbId: Long): String? = db.rawQuery(
        "SELECT serial FROM game_serials WHERE igdb_id = ? ORDER BY serial LIMIT 1",
        arrayOf(igdbId.toString())
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun loadGenresPreview(db: SQLiteDatabase, igdbId: Long): List<String> {
        val out = ArrayList<String>(4)
        db.rawQuery(
            """
            SELECT genre_name
            FROM game_genres
            WHERE igdb_id = ?
            ORDER BY genre_name COLLATE NOCASE ASC
            LIMIT 4
            """.trimIndent(),
            arrayOf(igdbId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += cursor.getString(0)
            }
        }
        return out
    }

    private fun cleanupTitle(value: String): String {
        return value
            .substringBeforeLast('.')
            .replace(Regex("""\[[^]]*]|\([^)]*\)"""), " ")
            .replace(Regex("""\b(disc|disk|cd|dvd)\s*[0-9]+\b"""), " ")
            .replace(Regex("""\b(v[0-9]+|ver\s*[0-9]+(\.[0-9]+)?|rev\s*[a-z0-9]+|patch\s*[0-9]+)\b"""), " ")
            .replace(Regex("""\b(usa|us|ntsc|pal|eur|europe|japan|jpn|korea|kor|france|germany|spain|esp|italy|russia|russian|beta|demo|proto|prototype|undub|unl|translated|en|fr|de|es|it|ru|pt|pl|ko|ja|jp)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeSearchText(value: String): String {
        val cleaned = cleanupTitle(value).lowercase(Locale.ROOT)
        val normalized = Normalizer.normalize(cleaned, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return normalized
            .replace("&", " and ")
            .replace("@", " at ")
            .replace(Regex("""\bviii\b"""), " 8 ")
            .replace(Regex("""\bvii\b"""), " 7 ")
            .replace(Regex("""\bvi\b"""), " 6 ")
            .replace(Regex("""\biv\b"""), " 4 ")
            .replace(Regex("""\biii\b"""), " 3 ")
            .replace(Regex("""\bii\b"""), " 2 ")
            .replace(Regex("""\bix\b"""), " 9 ")
            .replace(Regex("""\bxii\b"""), " 12 ")
            .replace(Regex("""\bxi\b"""), " 11 ")
            .replace(Regex("""\bv\b"""), " 5 ")
            .replace(Regex("""\bx\b"""), " 10 ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.normalizeSerialForMatch(): String {
        return trim().uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
    }

    private fun toHighResImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return url
        return url.replace(Regex("""/t_[^/]+/"""), "/t_1080p/")
    }
}

private const val MIN_FUZZY_TITLE_SCORE = 0.68

internal fun normalizeIdentityTitle(value: String): String {
    return Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("""\[[^]]*]|\([^)]*\)"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

internal fun catalogTitleSimilarity(left: String, right: String): Double {
    val leftTokens = catalogMatchTokens(left)
    val rightTokens = catalogMatchTokens(right)
    if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
    if (leftTokens == rightTokens) return 1.0
    val common = leftTokens.intersect(rightTokens).size
    if (common == 0 || (common == 1 && minOf(leftTokens.size, rightTokens.size) > 1)) return 0.0
    val candidateCoverage = common.toDouble() / leftTokens.size
    val catalogCoverage = common.toDouble() / rightTokens.size
    return candidateCoverage * 0.75 + catalogCoverage * 0.25
}

private fun catalogMatchTokens(value: String): Set<String> {
    return normalizeIdentityTitle(value)
        .split(' ')
        .asSequence()
        .filter { it.isNotBlank() && it !in CATALOG_TITLE_STOP_WORDS }
        .map { CATALOG_ROMAN_NUMERALS[it] ?: it }
        .toCollection(LinkedHashSet())
}

private val CATALOG_TITLE_STOP_WORDS = setOf("a", "an", "and", "of", "the")
private val CATALOG_ROMAN_NUMERALS = mapOf(
    "ii" to "2",
    "iii" to "3",
    "iv" to "4",
    "v" to "5",
    "vi" to "6",
    "vii" to "7",
    "viii" to "8",
    "ix" to "9",
    "x" to "10",
    "xi" to "11",
    "xii" to "12"
)

private fun android.database.Cursor.getStringOrNull(index: Int): String? {
    return if (isNull(index)) null else getString(index)
}

private fun android.database.Cursor.getIntOrNull(index: Int): Int? {
    return if (isNull(index)) null else getInt(index)
}

private fun android.database.Cursor.getDoubleOrNull(index: Int): Double? {
    return if (isNull(index)) null else getDouble(index)
}
