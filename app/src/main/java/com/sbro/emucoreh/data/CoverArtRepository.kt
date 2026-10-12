package com.sbro.emucoreh.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.sbro.emucoreh.core.DocumentPathResolver
import com.sbro.emucoreh.core.GameFormats
import com.sbro.emucoreh.core.NativeApp
import com.sbro.emucoreh.data.boxart.DiscGdtexExtractor
import com.sbro.emucoreh.data.boxart.FlycastBoxartSource
import com.sbro.emucoreh.data.boxart.TheGamesDb
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch

data class CoverCacheClearResult(
    val deletedFiles: Int,
    val freedBytes: Long,
    val failedFiles: Int
) {
    val fullyCleared: Boolean
        get() = failedFiles == 0
}

/**
 * Box art retrieval mirroring the official Flycast scraper:
 *  1. the 0GDTEX.PVR texture stored inside Dreamcast discs (offline scraper),
 *  2. arcade covers from the flycast-content site,
 *  3. TheGamesDB, matched by product code first and by name second.
 * The custom EmuCoreH cover server is no longer used.
 */
class CoverArtRepository(context: Context) {

    companion object {
        private const val TAG = "CoverArtRepository"
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 15000
        private const val MISS_TTL_MS = 24L * 60L * 60L * 1000L
        private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
        private val downloadFlightsLock = Any()
        private val downloadFlights = HashMap<String, PendingCoverDownload>()
        private val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp")
    }

    private class PendingCoverDownload {
        val completed = CountDownLatch(1)
        @Volatile var result: String? = null
    }

    private val context = context.applicationContext

    private val coverCacheDirectory = File(this.context.cacheDir, "game-covers")
    private val remoteImageCacheDirectory = File(this.context.cacheDir, "remote-image-cache")

    private val fetchDirectory by lazy {
        // The old EmuCoreH cover server cache is no longer used: drop it so the
        // library switches to the Flycast artwork on the next scan.
        runCatching {
            val legacy = File(coverCacheDirectory, "igdb-psp-v13")
            if (legacy.isDirectory) legacy.deleteRecursively()
        }
        File(coverCacheDirectory, "flycast").apply {
            if (!exists()) mkdirs()
            Log.d(TAG, "Box art cache directory created: $absolutePath")
        }
    }

    /** Clears only automatically downloaded covers. User-selected covers live in filesDir and are preserved. */
    fun clearCache(): CoverCacheClearResult = clearDirectories(listOf(coverCacheDirectory))

    /** Clears all temporary cover/image downloads without touching library metadata or custom artwork. */
    fun clearAllTemporaryImageCaches(): CoverCacheClearResult =
        clearDirectories(listOf(coverCacheDirectory, remoteImageCacheDirectory))

    fun isManagedCoverCachePath(path: String?): Boolean =
        CoverCachePolicy.isPathInside(path, coverCacheDirectory)

    /**
     * True for managed covers that only hold the offline GD-ROM label artwork:
     * Flycast shows the disc label immediately and replaces it with the online
     * box art once TheGamesDB answers, so these covers are still upgradeable.
     */
    fun isPendingOnlineCover(path: String?): Boolean {
        if (path.isNullOrBlank() || !isManagedCoverCachePath(path)) return false
        val key = File(path).name.substringBeforeLast('.')
        return !hasOnlineSource(key)
    }

    /** Online downloads record their source URL next to the cover file. */
    private fun hasOnlineSource(key: String): Boolean =
        File(fetchDirectory, "$key.url").isFile

    fun isMissingManagedCover(path: String?): Boolean =
        isManagedCoverCachePath(path) && !path.isNullOrBlank() && !isUsableCoverFile(File(path))

    /**
     * Never flash an embedded icon or a previous style before the selected
     * artwork loads. A missing managed-cache file must not erase a candidate
     * that was already resolved: the caller keeps showing the previous cover
     * while the background sync re-downloads it.
     */
    fun displayCoverPath(serial: String?, title: String?, candidate: String?): String? {
        if (candidate != null && !isManagedCoverCachePath(candidate)) return candidate
        return findCachedCoverPath(serial, title = title) ?: candidate
    }

    fun findCachedCoverPath(
        serial: String?,
        title: String? = null,
        ignoreDisabled: Boolean = false
    ): String? {
        if (!ignoreDisabled && !isFetchBoxartEnabled()) {
            return null
        }
        val coverKeys = coverKeys(serial, title)
        if (coverKeys.isEmpty()) {
            Log.d(TAG, "No serial or title provided")
            return null
        }
        for (key in coverKeys) {
            val found = findCoverFile(key)
            if (found != null) {
                Log.d(TAG, "Cached cover for $key: FOUND")
                return found.absolutePath
            }
        }
        Log.d(TAG, "Cached cover for ${coverKeys.joinToString()}: NOT FOUND")
        return null
    }

    fun findCachedCoverUri(
        serial: String?,
        title: String? = null
    ): String? = findCachedCoverPath(serial, title = title)

    /** Cache key used when a game has no product code (CHD, arcade archives). */
    private fun titleCoverKey(title: String?): String? = title
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("[^a-z0-9]+"), "_")
        ?.take(80)
        ?.takeIf { it.isNotBlank() }

    private fun coverKeys(serial: String?, title: String?): List<String> =
        listOfNotNull(normalizeSerial(serial), titleCoverKey(title)).distinct()

    fun downloadCover(game: GameItem, context: Context): String? {
        if (!isFetchBoxartEnabled()) {
            Log.d(TAG, "Cover download skipped: box art fetching is disabled")
            return null
        }
        val primaryKey = normalizeSerial(game.serial) ?: titleCoverKey(game.title)
        if (primaryKey == null) {
            Log.w(TAG, "Cannot download cover: no serial or title")
            return null
        }
        // Reuse artwork cached under the other key (title before the serial was
        // learned, or serial after a rename) instead of downloading a duplicate.
        // Offline label art is not final: it still needs the online upgrade.
        findCachedCoverPath(game.serial, title = game.title, ignoreDisabled = true)
            ?.takeIf { !isPendingOnlineCover(it) }
            ?.let { return it }
        lateinit var flight: PendingCoverDownload
        var ownsDownload = false
        synchronized(downloadFlightsLock) {
            flight = downloadFlights[primaryKey] ?: PendingCoverDownload().also {
                downloadFlights[primaryKey] = it
                ownsDownload = true
            }
        }
        if (!ownsDownload) {
            try {
                flight.completed.await()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            return flight.result
        }
        return try {
            downloadCoverResolved(game, primaryKey).also {
                flight.result = it
            }
        } finally {
            synchronized(downloadFlightsLock) {
                downloadFlights.remove(primaryKey, flight)
            }
            flight.completed.countDown()
        }
    }

    private fun downloadCoverResolved(game: GameItem, key: String): String? {
        Log.d(TAG, "========== BOX ART DOWNLOAD START ($key) ==========")
        val existingCover = findCachedCoverPath(game.serial, title = game.title, ignoreDisabled = true)
            ?.let(::File)
            ?: findCoverFile(key)
        if (existingCover != null && hasOnlineSource(key)) {
            Log.d(TAG, "Cover already exists: ${existingCover.absolutePath}")
            return existingCover.absolutePath
        }
        val missFile = File(fetchDirectory, "$key.miss")
        val missFresh = missFile.exists() &&
            System.currentTimeMillis() - missFile.lastModified() < MISS_TTL_MS

        var scrape: DiscScrapeResult? = null
        if (existingCover == null) {
            // Offline scraper, exactly like Flycast: the core opens the disc
            // image (GDI, CUE, ISO, CDI and CHD), reads the IP.BIN metadata and
            // extracts the 0GDTEX.PVR label artwork. It is shown immediately;
            // the online pass below upgrades it to the full box art later.
            scrape = scrapeDiscBoxart(game, key, extractCover = true)
            scrape?.coverPath?.let {
                missFile.delete()
                Log.d(TAG, "========== BOX ART DOWNLOAD END (GD-ROM texture) ==========")
                return it
            }
            // Fallback for content:// images the core cannot open directly.
            extractDiscBoxart(game, key)?.let {
                missFile.delete()
                Log.d(TAG, "========== BOX ART DOWNLOAD END (GD-ROM texture) ==========")
                return it
            }
        }
        if (missFresh) {
            Log.d(TAG, "Recent miss marker found, skipping")
            return existingCover?.absolutePath
        }

        var lookupSucceeded = false
        val arcade = isArcadeGame(game.fileName)
        try {
            if (arcade) {
                for (url in FlycastBoxartSource.arcadeImageUrls(game.fileName, game.serial)) {
                    downloadImage(url, key, missFile, "Flycast arcade", mute = true)?.let {
                        Log.d(TAG, "========== BOX ART DOWNLOAD END (arcade) ==========")
                        return acceptOnlineCover(existingCover, it)
                    }
                }
            }

            // Metadata for the online lookups when the label artwork already
            // exists (the image itself was extracted on the first pass).
            if (scrape == null) {
                scrape = scrapeDiscBoxart(game, key, extractCover = false)
            }

            val uniqueId = game.serial?.trim().orEmpty().ifBlank { scrape?.uniqueId.orEmpty() }
            if (uniqueId.isNotEmpty() && uniqueId != "T0000" && uniqueId != "T0000M") {
                val info = TheGamesDb.findByUniqueId(uniqueId)
                if (info != null) {
                    lookupSucceeded = true
                    info.imageUrl?.let { url ->
                        downloadImage(url, key, missFile, "TheGamesDB")?.let {
                            Log.d(TAG, "========== BOX ART DOWNLOAD END (TheGamesDB uid) ==========")
                            return acceptOnlineCover(existingCover, it)
                        }
                    }
                }
            }
            // TheGamesDB search name: the disc's own software name when the
            // image was readable, like Flycast, otherwise the library title.
            val searchName = scrape?.searchName
                ?: game.title.ifBlank { game.fileName.substringBeforeLast('.') }
            if (searchName.isNotBlank()) {
                val info = TheGamesDb.findByName(searchName)
                if (info != null) {
                    lookupSucceeded = true
                    info.imageUrl?.let { url ->
                        downloadImage(url, key, missFile, "TheGamesDB")?.let {
                            Log.d(TAG, "========== BOX ART DOWNLOAD END (TheGamesDB name) ==========")
                            return acceptOnlineCover(existingCover, it)
                        }
                    }
                }
            }
        } finally {
            if (lookupSucceeded) {
                missFile.writeText("not-found at ${System.currentTimeMillis()}")
            }
            Log.d(TAG, "========== BOX ART DOWNLOAD END ==========")
        }
        // No online artwork: keep the offline label art instead of a blank tile.
        return existingCover?.absolutePath
    }

    /** Drops the offline label artwork once the online cover replaced it. */
    private fun acceptOnlineCover(previousCover: File?, downloadedPath: String): String {
        if (previousCover != null && previousCover.absolutePath != downloadedPath) {
            runCatching { previousCover.delete() }
        }
        return downloadedPath
    }

    private data class DiscScrapeResult(
        val coverPath: String?,
        val searchName: String?,
        val uniqueId: String?
    )

    /**
     * Runs Flycast's offline scraper over the image through the core's disc
     * drivers. When [extractCover] is set the 0GDTEX.PVR cover is written to
     * the cache; otherwise only the IP.BIN metadata is read.
     */
    private fun scrapeDiscBoxart(game: GameItem, key: String, extractCover: Boolean): DiscScrapeResult? {
        if (isArcadeGame(game.fileName)) return null
        // Only disc images: playlists and other library content have no
        // ISO filesystem to scrape.
        val extension = game.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in GameFormats.discExtensions) return null
        val nativePath = nativeDiscPath(game.path) ?: return null
        val target = File(fetchDirectory, "$key.png")
        if (extractCover && target.exists()) target.delete()
        val metadata = NativeApp.scrapeDisc(nativePath, if (extractCover) target.absolutePath else null)
            ?: return null
        val fields = metadata.split('\n')
        return DiscScrapeResult(
            coverPath = if (extractCover) target.takeIf(::isUsableCoverFile)?.absolutePath else null,
            searchName = fields.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() },
            uniqueId = fields.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    /** The core's disc drivers only read real files, not SAF content URIs. */
    private fun nativeDiscPath(path: String): String? {
        if (!path.startsWith("content://")) return path
        return runCatching {
            DocumentPathResolver.resolveFilePath(context, path)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun extractDiscBoxart(game: GameItem, key: String): String? {
        if (isArcadeGame(game.fileName)) return null
        val bitmap = DiscGdtexExtractor.extractBitmap(context, game.path, game.fileName) ?: return null
        val target = File(fetchDirectory, "$key.png")
        return runCatching {
            if (target.exists()) target.delete()
            target.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            target.takeIf(::isUsableCoverFile)?.absolutePath
        }.getOrElse { error ->
            Log.w(TAG, "Unable to store GD-ROM texture: ${error.message}")
            target.delete()
            null
        }
    }

    /** Public artwork URL of a cached cover, used for shareable metadata. */
    fun buildPublicCoverUrl(serial: String?, title: String? = null): String? {
        for (key in coverKeys(serial, title)) {
            val urlFile = File(fetchDirectory, "$key.url")
            if (!urlFile.isFile) continue
            val url = runCatching { urlFile.readText().trim() }.getOrNull().orEmpty()
            if (url.startsWith("http://") || url.startsWith("https://")) return url
        }
        return null
    }

    private fun downloadImage(
        urlString: String,
        key: String,
        missFile: File,
        sourceName: String,
        mute: Boolean = false
    ): String? {
        val connection = (URL(urlString).openConnection() as? HttpURLConnection)
            ?: run {
                Log.e(TAG, "$sourceName: Failed to create HTTP connection")
                return null
            }
        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (EmuCoreH)")
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                missFile.writeText("404 at ${System.currentTimeMillis()}")
            }
            if (responseCode != HttpURLConnection.HTTP_OK) {
                if (!mute) Log.w(TAG, "$sourceName: Cover not found (HTTP $responseCode)")
                return null
            }
            val contentType = connection.contentType.orEmpty()
            if (!contentType.startsWith("image/")) {
                if (!mute) Log.w(TAG, "$sourceName: Bad content type $contentType")
                return null
            }
            val contentLength = connection.contentLength
            if (contentLength == 0 || contentLength > MAX_IMAGE_BYTES) {
                if (!mute) Log.w(TAG, "$sourceName: Invalid content length")
                return null
            }

            val extension = imageExtension(urlString)
            val coverFile = File(fetchDirectory, "$key.$extension")
            val tempFile = File.createTempFile("$key.", ".tmp", fetchDirectory)
            try {
                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        var copied = 0L
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= MAX_IMAGE_BYTES) { "Cover exceeds size limit" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (!isUsableCoverFile(tempFile)) {
                    if (!mute) Log.w(TAG, "$sourceName: Downloaded file is not a valid image")
                    return null
                }
                if (coverFile.exists()) coverFile.delete()
                if (!tempFile.renameTo(coverFile)) {
                    tempFile.copyTo(coverFile, overwrite = true)
                }
                if (!isUsableCoverFile(coverFile)) {
                    coverFile.delete()
                    return null
                }
                // Drop other formats of the same key: a cached GD-ROM label
                // (.png) must not shadow the online box art (.jpg).
                for (extension in IMAGE_EXTENSIONS) {
                    val sibling = File(fetchDirectory, "$key.$extension")
                    if (sibling != coverFile && sibling.isFile) {
                        runCatching { sibling.delete() }
                    }
                }
                runCatching { File(fetchDirectory, "$key.url").writeText(urlString) }
                missFile.delete()
                Log.d(TAG, "$sourceName: SUCCESS - ${coverFile.absolutePath}")
                coverFile.absolutePath
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            if (!mute) Log.e(TAG, "$sourceName: Error: ${e.message}", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun findCoverFile(key: String): File? {
        for (extension in IMAGE_EXTENSIONS) {
            val file = File(fetchDirectory, "$key.$extension")
            if (file.isFile && file.length() <= 0L) {
                Log.w(TAG, "Removing empty cached cover: ${file.absolutePath}")
                runCatching { file.delete() }
                continue
            }
            if (isUsableCoverFile(file)) return file
        }
        return null
    }

    private fun isArcadeGame(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in GameFormats.romExtensions
    }

    private fun imageExtension(urlString: String): String {
        val path = urlString.substringBefore('?').substringBefore('#')
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (extension) {
            "png", "jpg", "jpeg", "webp" -> extension
            else -> "jpg"
        }
    }

    private fun normalizeSerial(serial: String?): String? {
        if (serial.isNullOrBlank()) return null

        val regex = Regex("([A-Za-z]{4})[^a-zA-Z0-9]*([0-9]{3})[^a-zA-Z0-9]*([0-9]{2})")
        val altRegex = Regex("([A-Za-z]{4})[^a-zA-Z0-9]*([0-9]{5})")

        val cleanSerial = serial.trim().uppercase(Locale.ROOT)
        var formatted: String? = null

        val match = regex.find(cleanSerial)
        if (match != null) {
            formatted = "${match.groupValues[1]}-${match.groupValues[2]}${match.groupValues[3]}"
        } else {
            val altMatch = altRegex.find(cleanSerial)
            if (altMatch != null) {
                formatted = "${altMatch.groupValues[1]}-${altMatch.groupValues[2]}"
            }
        }

        return (formatted ?: cleanSerial.replace(Regex("[^A-Z0-9_-]"), ""))
            .also { Log.d(TAG, "Normalized: '$serial' -> '$it'") }
    }

    private fun isFetchBoxartEnabled(): Boolean =
        AppPreferences(context).getFetchBoxartSync()

    private fun isUsableCoverFile(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        return runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth > 0 && options.outHeight > 0
        }.getOrDefault(false)
    }

    private fun clearDirectories(directories: List<File>): CoverCacheClearResult {
        var deletedFiles = 0
        var freedBytes = 0L
        var failedFiles = 0

        directories.distinctBy { it.absolutePath }.forEach { directory ->
            if (!directory.exists()) return@forEach
            val root = runCatching { directory.canonicalFile }.getOrNull() ?: run {
                failedFiles++
                return@forEach
            }
            directory.walkBottomUp().forEach { entry ->
                if (entry == directory) return@forEach
                val safeEntry = runCatching { entry.canonicalFile }.getOrNull()
                if (safeEntry == null || !CoverCachePolicy.isFileInside(safeEntry, root)) {
                    failedFiles++
                    return@forEach
                }
                if (entry.isFile) {
                    val size = entry.length()
                    if (runCatching { entry.delete() }.getOrDefault(false)) {
                        deletedFiles++
                        freedBytes += size
                    } else {
                        failedFiles++
                    }
                } else if (entry.isDirectory) {
                    // Remove only now-empty cache subdirectories; the two cache roots remain available.
                    runCatching { entry.delete() }
                }
            }
        }
        return CoverCacheClearResult(deletedFiles, freedBytes, failedFiles)
    }

}
