package com.sbro.emucoreh.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
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

class CoverArtRepository(context: Context) {

    companion object {
        private const val TAG = "CoverArtRepository"
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 15000
        private const val MISS_TTL_MS = 7L * 24L * 60L * 60L * 1000L // 7 days
        private val downloadFlightsLock = Any()
        private val downloadFlights = HashMap<String, PendingCoverDownload>()
    }

    private class PendingCoverDownload {
        val completed = CountDownLatch(1)
        @Volatile var result: String? = null
    }

    private val context = context.applicationContext

    private val coverCacheDirectory = File(this.context.cacheDir, "game-covers")
    private val remoteImageCacheDirectory = File(this.context.cacheDir, "remote-image-cache")

    private val cacheDirectory by lazy {
        File(coverCacheDirectory, "igdb-psp-v13").apply {
            if (!exists()) mkdirs()
            Log.d(TAG, "Cover cache directory created: $absolutePath")
        }
    }

    /** Clears only automatically downloaded covers. User-selected covers live in filesDir and are preserved. */
    fun clearCache(): CoverCacheClearResult = clearDirectories(listOf(coverCacheDirectory))

    /** Clears all temporary cover/image downloads without touching library metadata or custom artwork. */
    fun clearAllTemporaryImageCaches(): CoverCacheClearResult =
        clearDirectories(listOf(coverCacheDirectory, remoteImageCacheDirectory))

    fun isManagedCoverCachePath(path: String?): Boolean =
        CoverCachePolicy.isPathInside(path, coverCacheDirectory)

    fun isMissingManagedCover(path: String?): Boolean =
        isManagedCoverCachePath(path) && !path.isNullOrBlank() && !isUsableCoverFile(File(path))

    fun hasIndexedCover(serial: String?, title: String?): Boolean =
        com.sbro.emucoreh.data.catalog.CoverIndexRepository(context).find(serial, title) != null

    /** Never flash an embedded icon or a previous style before the selected artwork loads. */
    fun displayCoverPath(serial: String?, title: String?, candidate: String?): String? {
        if (candidate != null && !isManagedCoverCachePath(candidate)) return candidate
        if (hasIndexedCover(serial, title)) return findCachedCoverPath(serial, title = title)
        return candidate
    }

    fun findCachedCoverPath(
        serial: String?,
        styleOverride: Int? = null,
        ignoreDisabled: Boolean = false,
        title: String? = null
    ): String? {
        val style = resolveCoverArtStyle(styleOverride)
        if (!ignoreDisabled && style == AppPreferences.COVER_ART_STYLE_DISABLED) {
            return null
        }
        // CHD and arcade archives carry no product code, so the cache key falls
        // back to the filename-derived title, exactly like the downloader.
        val normalizedSerial = normalizeSerial(serial) ?: titleCoverKey(title)
        if (normalizedSerial == null) {
            Log.d(TAG, "No serial or title provided")
            return null
        }
        val preferredFiles = if (style == AppPreferences.COVER_ART_STYLE_3D) {
            listOf(
                File(cacheDirectory, "${normalizedSerial}_3d.webp"),
                File(cacheDirectory, "${normalizedSerial}_3d.png"),
                File(cacheDirectory, "${normalizedSerial}_3d.jpg")
            )
        } else {
            listOf(
                File(cacheDirectory, "$normalizedSerial.jpg"),
                File(cacheDirectory, "$normalizedSerial.png")
            )
        }
        preferredFiles.filter(File::exists).forEach { file ->
            if (!isUsableCoverFile(file)) {
                Log.w(TAG, "Removing invalid cached cover: ${file.absolutePath}")
                runCatching { file.delete() }
            }
        }
        val found = preferredFiles.firstOrNull(::isUsableCoverFile)
        Log.d(TAG, "Cached cover for $normalizedSerial (style=$style): ${if (found != null) "FOUND" else "NOT FOUND"}")
        return found?.absolutePath
    }

    fun findCachedCoverUri(
        serial: String?,
        styleOverride: Int? = null,
        ignoreDisabled: Boolean = false,
        title: String? = null
    ): String? {
        return findCachedCoverPath(serial, styleOverride, ignoreDisabled, title)
    }

    /** Cache key used when a game has no product code (CHD, arcade archives). */
    fun titleCoverKey(title: String?): String? = title
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("[^a-z0-9]+"), "_")
        ?.take(80)
        ?.takeIf { it.isNotBlank() }

    fun downloadCover(
        serial: String?,
        styleOverride: Int? = null,
        ignoreDisabled: Boolean = false,
        title: String? = null
    ): String? {
        val style = resolveCoverArtStyle(styleOverride)
        if (!ignoreDisabled && style == AppPreferences.COVER_ART_STYLE_DISABLED) {
            Log.d(TAG, "Cover download skipped: cover art style is disabled")
            return null
        }
        val normalizedSerial = normalizeSerial(serial)
        if (normalizedSerial == null && title.isNullOrBlank()) {
            Log.w(TAG, "Cannot download cover: no PSP serial or title")
            return null
        }
        val coverBaseUrl = resolveCoverBaseUrl(style)
        val coverKey = normalizedSerial ?: title!!.trim().lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_").take(80)
        val flightKey = "$style|$coverBaseUrl|$coverKey"
        lateinit var flight: PendingCoverDownload
        var ownsDownload = false
        synchronized(downloadFlightsLock) {
            flight = downloadFlights[flightKey] ?: PendingCoverDownload().also {
                downloadFlights[flightKey] = it
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
            downloadCoverResolved(serial, coverKey, style, coverBaseUrl, title).also {
                flight.result = it
            }
        } finally {
            synchronized(downloadFlightsLock) {
                downloadFlights.remove(flightKey, flight)
            }
            flight.completed.countDown()
        }
    }

    private fun downloadCoverResolved(
        originalSerial: String?,
        normalizedSerial: String,
        style: Int,
        coverBaseUrl: String?,
        title: String?
    ): String? {
        val targetExtension = if (style == AppPreferences.COVER_ART_STYLE_3D) "webp" else "jpg"

        Log.d(TAG, "========== COVER DOWNLOAD START ==========")
        Log.d(TAG, "Original serial: $originalSerial")
        Log.d(TAG, "Normalized serial: $normalizedSerial")
        Log.d(TAG, "Cover base URL: $coverBaseUrl")
        Log.d(TAG, "Cover style: $style")

        val coverFile = File(cacheDirectory, cacheFileName(normalizedSerial, style, targetExtension))
        if (isUsableCoverFile(coverFile)) {
            Log.d(TAG, "Cover already exists: ${coverFile.absolutePath}")
            Log.d(TAG, "========== COVER DOWNLOAD END (CACHED) ==========")
            return coverFile.absolutePath
        }
        if (coverFile.exists()) coverFile.delete()

        val missFile = File(cacheDirectory, cacheMissFileName(normalizedSerial, style))
        if (missFile.exists() && System.currentTimeMillis() - missFile.lastModified() < MISS_TTL_MS) {
            Log.d(TAG, "Recent miss marker found, skipping (age: ${System.currentTimeMillis() - missFile.lastModified()}ms)")
            Log.d(TAG, "========== COVER DOWNLOAD END (MISS CACHED) ==========")
            return null
        }

        var result: String? = null
        if (coverBaseUrl != null) {
            val extensionsToTry = if (style == AppPreferences.COVER_ART_STYLE_3D) listOf("png", "jpg") else listOf("jpg", "png")
            for (extension in extensionsToTry) {
                val targetFile = File(cacheDirectory, cacheFileName(normalizedSerial, style, extension))
                result = downloadFromUrl("$coverBaseUrl/$normalizedSerial.$extension", targetFile, missFile, "Custom")
                if (result != null) break
            }
        } else {
            val cover = com.sbro.emucoreh.data.catalog.CoverIndexRepository(context).find(originalSerial, title, style == AppPreferences.COVER_ART_STYLE_3D)
            if (cover != null) {
                // A catalogue hit invalidates a previous miss: earlier attempts
                // may have failed while the cover index was still incomplete.
                if (missFile.exists()) missFile.delete()
                result = downloadFromUrl(cover.url, coverFile, missFile, "EmuCoreH covers", cover.sha256)
                if (result == null && style != AppPreferences.COVER_ART_STYLE_3D) {
                    result = downloadFromUrl(cover.sourceUrl, coverFile, missFile, "IGDB PSP catalog")
                }
            }
        }

        Log.d(TAG, "Download result: ${if (result != null) "SUCCESS" else "FAILED"}")
        Log.d(TAG, "========== COVER DOWNLOAD END ==========")
        return result
    }

    fun buildPublicCoverUrl(
        serial: String?,
        styleOverride: Int? = null,
        title: String? = null
    ): String? {
        val normalizedSerial = normalizeSerial(serial)
        val style = resolveCoverArtStyle(styleOverride)
        if (style == AppPreferences.COVER_ART_STYLE_DISABLED) return null
        val baseUrl = resolveCoverBaseUrl(style)
        if (baseUrl != null && normalizedSerial != null) return "$baseUrl/$normalizedSerial.${if (style == AppPreferences.COVER_ART_STYLE_3D) "png" else "jpg"}"
        return com.sbro.emucoreh.data.catalog.CoverIndexRepository(context)
            .find(serial, title, style == AppPreferences.COVER_ART_STYLE_3D)?.url
    }

    private fun downloadFromUrl(
        urlString: String,
        coverFile: File,
        missFile: File,
        sourceName: String,
        expectedSha256: String? = null
    ): String? {
        if (isUsableCoverFile(coverFile)) {
            return coverFile.absolutePath
        }
        if (coverFile.exists()) coverFile.delete()

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
            Log.d(TAG, "$sourceName: HTTP response code: $responseCode")

            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "$sourceName: Cover not found (HTTP $responseCode)")
                if (responseCode == 404) {
                    missFile.writeText("$responseCode at ${System.currentTimeMillis()}")
                }
                return null
            }

            val contentLength = connection.contentLength
            Log.d(TAG, "$sourceName: Content length: $contentLength bytes")

            // Chunked HTTP responses legitimately report -1. Only an explicitly empty body is invalid.
            if (contentLength == 0 || contentLength > 16 * 1024 * 1024) {
                Log.w(TAG, "$sourceName: Invalid content length")
                return null
            }

            val tempFile = File.createTempFile("${coverFile.name}.", ".tmp", cacheDirectory)
            try {
                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        var copied = 0L
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= 16 * 1024 * 1024) { "Cover exceeds size limit" }
                            output.write(buffer, 0, count)
                        }
                        Log.d(TAG, "$sourceName: Copied $copied bytes")
                    }
                }

                if (expectedSha256 != null) {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    tempFile.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (actual != expectedSha256) return null
                }
                if (!isUsableCoverFile(tempFile)) {
                    Log.w(TAG, "$sourceName: Downloaded file is not a valid image")
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
                missFile.delete()

                Log.d(TAG, "$sourceName: SUCCESS - ${coverFile.absolutePath}")
                coverFile.absolutePath
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "$sourceName: Error: ${e.message}", e)
            null
        } finally {
            connection.disconnect()
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

    private fun resolveCoverBaseUrl(style: Int = resolveCoverArtStyle()): String? {
        val preferences = AppPreferences(context)
        val configuredUrls = preferences.getCoverDownloadBaseUrlSync()
            ?.split(Regex("\\s+"))
            ?.map { it.trim().trimEnd('/') }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (configuredUrls.isNotEmpty()) {
            return if (style == AppPreferences.COVER_ART_STYLE_3D) {
                configuredUrls.getOrNull(1) ?: configuredUrls.first()
            } else {
                configuredUrls.first()
            }
        }
        return null
    }

    private fun resolveCoverArtStyle(styleOverride: Int? = null): Int {
        return styleOverride ?: AppPreferences(context).getCoverArtStyleSync()
    }

    private fun cacheFileName(serial: String, style: Int, extension: String): String {
        return if (style == AppPreferences.COVER_ART_STYLE_3D) {
            "${serial}_3d.$extension"
        } else {
            "$serial.$extension"
        }
    }

    private fun cacheMissFileName(serial: String, style: Int): String {
        return if (style == AppPreferences.COVER_ART_STYLE_3D) {
            "${serial}_3d.miss"
        } else {
            "$serial.miss"
        }
    }

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
