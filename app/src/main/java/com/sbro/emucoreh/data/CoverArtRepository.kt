package com.sbro.emucoreh.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.sbro.emucoreh.core.DocumentPathResolver
import com.sbro.emucoreh.core.GameFormats
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.core.SegaPlatformDetector
import com.sbro.emucoreh.data.igdb.catalogPlatformName
import com.sbro.emucoreh.core.NativeApp
import com.sbro.emucoreh.data.boxart.DiscGdtexExtractor
import com.sbro.emucoreh.data.boxart.DiscScrapeAccess
import com.sbro.emucoreh.data.boxart.BoxartRetryException
import com.sbro.emucoreh.data.boxart.CoverFetchMode
import com.sbro.emucoreh.data.boxart.CoverFetchOrder
import com.sbro.emucoreh.data.boxart.ArcadeLandscapeCoverRefresh
import com.sbro.emucoreh.data.boxart.FlycastCoverCache
import com.sbro.emucoreh.data.boxart.FlycastImageDownloader
import com.sbro.emucoreh.data.boxart.FlycastBoxartSource
import com.sbro.emucoreh.data.boxart.TheGamesDb
import com.sbro.emucoreh.data.boxart.PreparedBoxartLookup
import com.sbro.emucoreh.core.SafStorageBridge
import com.sbro.emucoreh.data.catalog.CoverIndexRepository
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.sbro.emucoreh.ui.common.clearCoverImageMemoryCache

data class CoverCacheClearResult(
    val deletedFiles: Int,
    val freedBytes: Long,
    val failedFiles: Int
) {
    val fullyCleared: Boolean
        get() = failedFiles == 0
}

/** Preferences that affect which cached cover file is selected. */
data class CoverDisplaySettings(
    val enabled: Boolean,
    val style: Int,
    val isLight: Boolean
)

/**
 * Box art retrieval using Flycast's offline scraper and the EmuCoreH catalog:
 *  1. cached artwork, then EmuCoreH-Covers by known product code and title,
 *  2. the disc's 0GDTEX.PVR texture when offline or outside our catalog,
 *  3. disc metadata, Flycast arcade artwork and TheGamesDB as fallbacks.
 */
class CoverArtRepository(context: Context) {

    companion object {
        private const val TAG = "CoverArtRepository"
        private const val MISS_TTL_MS = 24L * 60L * 60L * 1000L
        private val downloadFlightsLock = Any()
        private val downloadFlights = HashMap<String, PendingCoverDownload>()
    }

    private class PendingCoverDownload(val mode: CoverFetchMode) {
        val completed = CountDownLatch(1)
        @Volatile var result: String? = null
    }

    private val context = context.applicationContext

    private val coverCacheDirectory = File(this.context.cacheDir, "game-covers")
    private val remoteImageCacheDirectory = File(this.context.cacheDir, "remote-image-cache")
    private val discMetadata = ConcurrentHashMap<String, DiscScrapeResult>()
    private val missingImageUrls = ConcurrentHashMap.newKeySet<String>()
    private val prefetchedInfo = PreparedBoxartLookup()
    private val retryAt = AtomicLong(0L)
    val nextOnlineRetryAtMillis: Long get() = retryAt.get()
    private val cache by lazy { FlycastCoverCache(fetchDirectory, ::isUsableCoverFile) }
    private val imageDownloader by lazy { FlycastImageDownloader(fetchDirectory, ::isUsableCoverFile) }
    private val coverIndex by lazy { CoverIndexRepository(this.context) }

    private val fetchDirectory by lazy {
        // The old EmuCoreH cover server cache is no longer used: drop it so the
        // library switches to the Flycast artwork on the next scan.
        runCatching {
            val legacy = File(coverCacheDirectory, "igdb-psp-v13")
            if (legacy.isDirectory) legacy.deleteRecursively()
        }
        File(coverCacheDirectory, "flycast").apply {
            if (!exists()) mkdirs()
            // Invalidate old uncropped/padded 3D covers once so the app downloads
            // the new tight-cropped 3D covers from EmuCoreH-Covers.
            val marker = File(this, ".tight_3d_v2.marker")
            if (!marker.exists()) {
                runCatching {
                    listFiles()?.forEach { file ->
                        if (file.name.endsWith(".webp") || file.name.endsWith(".url") || file.name.endsWith(".miss")) {
                            file.delete()
                        }
                    }
                    marker.createNewFile()
                    clearCoverImageMemoryCache()
                }
            }
            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.d(TAG, "Box art cache directory created: $absolutePath")
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
        return !cache.isOnline(File(path))
    }

    /** Resolve raw disc UIDs in batches, as Flycast does, before parallel downloads. */
    fun prepareOnlineCovers(games: List<GameItem>, shouldAbort: () -> Boolean = { false }) {
        retryAt.set(0L)
        if (!isFetchBoxartEnabled()) return
        if (!isNetworkAvailable()) {
            postponeOnlineCovers()
            return
        }
        prefetchedInfo.begin()
        val ids = games.filter { game ->
            !isSegaGame(game) && !isArcadeGame(game.fileName) &&
                findCachedCoverPath(game.serial, game.title, ignoreDisabled = true)
                    .let { it == null || isPendingOnlineCover(it) } &&
                !hasRecentMiss(game)
        }.flatMap { game ->
            if (shouldAbort()) return@flatMap emptyList()
            val key = normalizeSerial(game.serial) ?: titleCoverKey(game.title) ?: return@flatMap emptyList()
            val scrape = scrapeDiscBoxart(game, key, extractCover = false)
            FlycastBoxartSource.uniqueIdCandidates(scrape?.uniqueId, game.serial)
        }.distinct().filter { uniqueId ->
            when {
                prefetchedInfo.contains(uniqueId) -> false
                coverIndex.findCoverBySerial(uniqueId) != null -> {
                    prefetchedInfo.skipForCatalog(uniqueId)
                    false
                }
                else -> true
            }
        }
        for (batch in ids.chunked(10)) {
            if (shouldAbort()) break
            try {
                prefetchedInfo.add(TheGamesDb.findByUniqueIds(batch))
            } catch (failure: BoxartRetryException) {
                noteRetry(failure.retryAtMillis)
                if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.d(TAG, "Online cover batch postponed: ${failure.message}")
                break
            }
        }
    }

    private fun noteRetry(atMillis: Long = System.currentTimeMillis() + 1000L) {
        retryAt.accumulateAndGet(atMillis, ::maxOf)
    }

    fun postponeOnlineCovers() = noteRetry()

    fun isNetworkAvailable(): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        val network = manager.activeNetwork ?: return@runCatching false
        manager.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    private fun hasRecentMiss(game: GameItem): Boolean {
        val key = coverKeys(game.serial, game.title, gamePlatform(game)).firstOrNull() ?: return false
        val miss = coverMissFile(key)
        return miss.isFile && System.currentTimeMillis() - miss.lastModified() < MISS_TTL_MS
    }

    private fun coverMissFile(key: String): File = if (key.startsWith("sega_"))
        File(fetchDirectory, "$key.miss-sega-v2") else cache.missFile(key)

    fun isMissingManagedCover(path: String?): Boolean =
        isManagedCoverCachePath(path) && !path.isNullOrBlank() && !isUsableCoverFile(File(path))

    fun isMismatchedManagedCover(path: String?, platform: String?): Boolean =
        !path.isNullOrBlank() && isManagedCoverCachePath(path) &&
            !coverCacheFileMatchesPlatform(File(path).name, platform)

    /**
     * Prefer the selected online artwork, with disc artwork as an offline
     * fallback. A missing managed-cache file must not erase a candidate
     * that was already resolved: the caller keeps showing the previous cover
     * while the background sync re-downloads it.
     */
    fun displayCoverSettings(): CoverDisplaySettings {
        val prefs = AppPreferences(context)
        return CoverDisplaySettings(
            enabled = prefs.getFetchBoxartSync(),
            style = prefs.getCoverArtStyleSync(),
            isLight = prefs.isThemeLightSync()
        )
    }

    fun displayCoverPath(serial: String?, title: String?, candidate: String?, platform: String? = null): String? =
        displayCoverPath(serial, title, candidate, platform, displayCoverSettings())

    /** Batch callers can read the display settings once instead of for every game. */
    fun displayCoverPath(
        serial: String?,
        title: String?,
        candidate: String?,
        platform: String?,
        settings: CoverDisplaySettings
    ): String? {
        if (candidate != null && !isManagedCoverCachePath(candidate)) return candidate
        return findCachedCoverPath(
            serial = serial,
            title = title,
            ignoreDisabled = false,
            platform = platform,
            settings = settings
        ) ?: candidate.takeUnless { isMismatchedManagedCover(it, platform) }
    }

    fun findCachedCoverPath(
        serial: String?,
        title: String? = null,
        ignoreDisabled: Boolean = false,
        platform: String? = null
    ): String? = findCachedCoverPath(
        serial = serial,
        title = title,
        ignoreDisabled = ignoreDisabled,
        platform = platform,
        settings = displayCoverSettings()
    )

    private fun findCachedCoverPath(
        serial: String?,
        title: String?,
        ignoreDisabled: Boolean,
        platform: String?,
        settings: CoverDisplaySettings
    ): String? {
        if (!ignoreDisabled && !settings.enabled) {
            return null
        }
        val coverKeys = coverKeys(serial, title, platform)
        if (coverKeys.isEmpty()) {
            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.d(TAG, "No serial or title provided")
            return null
        }
        cache.find(coverKeys, settings.style, settings.isLight)?.let { found ->
            return found.absolutePath
        }
        if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.d(TAG, "Cached cover for ${coverKeys.joinToString()}: NOT FOUND")
        return null
    }

    fun findCachedCoverUri(
        serial: String?,
        title: String? = null,
        platform: String? = null
    ): String? = findCachedCoverPath(serial, title = title, platform = platform)

    /** Cache key used when a game has no product code (CHD, arcade archives). */
    private fun titleCoverKey(title: String?): String? = title
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("[^a-z0-9]+"), "_")
        ?.take(80)
        ?.takeIf { it.isNotBlank() }

    private fun coverKeys(serial: String?, title: String?, platform: String? = null): List<String> =
        listOfNotNull(normalizeSerial(serial), titleCoverKey(title)).distinct().map { scopedCoverCacheKey(it, platform) }

    private fun gamePlatform(game: GameItem): String = game.platform ?: SegaPlatformDetector.detectName(game.fileName).name
    private fun isSegaGame(game: GameItem): Boolean =
        catalogPlatformName(gamePlatform(game)) in setOf("Genesis", "Sega CD", "32X", "Master System", "Game Gear", "SG-1000", "Sega Model 3")

    fun downloadCover(game: GameItem, mode: CoverFetchMode = CoverFetchMode.FULL): String? {
        if (!isFetchBoxartEnabled()) {
            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.d(TAG, "Cover download skipped: box art fetching is disabled")
            return null
        }
        val primaryKey = coverKeys(game.serial, game.title, gamePlatform(game)).firstOrNull()
        if (primaryKey == null) {
            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.w(TAG, "Cannot download cover: no serial or title")
            return null
        }
        // The replaced arcade assets keep the same URL. Refresh their downloaded
        // copies once, before the ordinary cache hit can short-circuit the fetch.
        val networkAvailable = mode != CoverFetchMode.OFFLINE_ONLY && isNetworkAvailable()
        if (networkAvailable && ArcadeLandscapeCoverRefresh.refresh(fetchDirectory)) {
            clearCoverImageMemoryCache()
        }
        // Reuse artwork cached under the other key (title before the serial was
        // learned, or serial after a rename) instead of downloading a duplicate.
        // Offline label art is not final: it still needs the online upgrade.
        findCachedCoverPath(game.serial, title = game.title, ignoreDisabled = true, platform = gamePlatform(game))
            ?.takeIf { !isPendingOnlineCover(it) }
            ?.let { return it }
        val effectiveMode = if (mode != CoverFetchMode.OFFLINE_ONLY && !networkAvailable) {
            postponeOnlineCovers()
            if (mode == CoverFetchMode.REPOSITORY_ONLY) {
                return findCachedCoverPath(game.serial, title = game.title, ignoreDisabled = true, platform = gamePlatform(game))
            }
            CoverFetchMode.OFFLINE_ONLY
        } else mode
        lateinit var flight: PendingCoverDownload
        var ownsDownload = false
        synchronized(downloadFlightsLock) {
            flight = downloadFlights[primaryKey] ?: PendingCoverDownload(effectiveMode).also {
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
            // Serialize writes by cover key, but do not mistake a fast miss or
            // an offline label for a completed lookup in another mode.
            return if (flight.mode == effectiveMode) flight.result else downloadCover(game, mode)
        }
        return try {
            downloadCoverResolved(game, primaryKey, effectiveMode).also {
                flight.result = it
            }
        } finally {
            synchronized(downloadFlightsLock) {
                downloadFlights.remove(primaryKey, flight)
            }
            flight.completed.countDown()
        }
    }

    private fun downloadCoverResolved(game: GameItem, key: String, mode: CoverFetchMode): String? {
        if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.d(TAG, "========== BOX ART DOWNLOAD START ($key) ==========")
        val existingCover = findCachedCoverPath(game.serial, title = game.title, ignoreDisabled = true, platform = gamePlatform(game))
            ?.let(::File)
            ?: findCoverFile(key)
        val prefs = AppPreferences(context)
        val style = prefs.getCoverArtStyleSync()
        val isLight = prefs.isThemeLightSync()
        if (isSegaGame(game)) {
            if (existingCover != null && cache.isOnline(existingCover)) return existingCover.absolutePath
            if (mode == CoverFetchMode.OFFLINE_ONLY) return existingCover?.absolutePath
            val miss = coverMissFile(key)
            if (miss.isFile && System.currentTimeMillis() - miss.lastModified() < MISS_TTL_MS) return existingCover?.absolutePath
            val downloaded = downloadRepositoryCover(game, key, style, isLight)
            if (downloaded != null) miss.delete() else miss.writeText("not-found at ${System.currentTimeMillis()}")
            return downloaded ?: existingCover?.absolutePath
        }
        if (existingCover != null && cache.isOnline(existingCover)) {
            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.d(TAG, "Cover already exists and matches style and theme: ${existingCover.absolutePath}")
            return existingCover.absolutePath
        }
        val missFile = coverMissFile(key)
        val missFresh = missFile.exists() &&
            System.currentTimeMillis() - missFile.lastModified() < MISS_TTL_MS

        var scrape: DiscScrapeResult? = null
        return CoverFetchOrder.resolve(
            mode = mode,
            cached = existingCover?.absolutePath,
            repository = {
                if (missFresh) null else downloadRepositoryCover(game, key, style, isLight)
            },
            disc = {
                scrape = scrapeDiscBoxart(game, key, extractCover = true)
                scrape?.coverPath ?: extractDiscBoxart(game, key)
            },
            fallback = fallback@{ labelPath ->
                if (missFresh) return@fallback null
                val label = labelPath?.let(::File)
                val arcade = isArcadeGame(game.fileName)
                if (arcade) {
                    for (url in FlycastBoxartSource.arcadeImageUrls(game.fileName, game.serial)) {
                        downloadImage(url, key, "Flycast arcade", mute = true)?.let {
                            return@fallback acceptOnlineCover(label, it)
                        }
                    }
                }

                // Metadata for the online lookups when the label artwork already
                // exists (the image itself was extracted on the first pass).
                if (scrape == null) {
                    scrape = scrapeDiscBoxart(game, key, extractCover = false)
                }

                val uniqueIds = if (arcade) emptyList() else
                    FlycastBoxartSource.uniqueIdCandidates(scrape?.uniqueId, game.serial)

                // 1. EmuCoreH-Covers GitHub repository by UID / product code first
                for (uniqueId in uniqueIds) {
                    val repoUrl = coverIndex.findCoverBySerial(uniqueId, style, isLight)
                    if (repoUrl != null) {
                        downloadImage(repoUrl, key, "EmuCoreH-Covers serial")?.let {
                            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.d(TAG, "========== BOX ART DOWNLOAD END (EmuCoreH-Covers serial) ==========")
                            return@fallback acceptOnlineCover(label, it)
                        }
                    }
                }

                // 2. EmuCoreH-Covers GitHub repository by game title second
                val searchName = FlycastBoxartSource.searchName(
                    scrape?.searchName ?: game.title.ifBlank { game.fileName.substringBeforeLast('.') }, arcade
                )
                if (searchName.isNotBlank()) {
                    val repoTitleUrl = coverIndex.findCoverByTitle(searchName, style, isLight)
                        ?: coverIndex.findCoverByTitle(game.title, style, isLight)
                    if (repoTitleUrl != null) {
                        downloadImage(repoTitleUrl, key, "EmuCoreH-Covers title")?.let {
                            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.d(TAG, "========== BOX ART DOWNLOAD END (EmuCoreH-Covers title) ==========")
                            return@fallback acceptOnlineCover(label, it)
                        }
                    }
                }

                // 3. Fallback: TheGamesDB API (if game not found in our repository, e.g. homebrew)
                for (uniqueId in uniqueIds) {
                    val info = prefetchedInfo.find(
                        uniqueId, maxOf(nextOnlineRetryAtMillis, System.currentTimeMillis() + 1000L)
                    ) { TheGamesDb.findByUniqueId(uniqueId) }
                    if (info != null) {
                        info.imageUrl?.let { url ->
                            downloadImage(url, key, "TheGamesDB")?.let {
                                if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.d(TAG, "========== BOX ART DOWNLOAD END (TheGamesDB uid) ==========")
                                return@fallback acceptOnlineCover(label, it)
                            }
                        }
                    }
                }
                if (searchName.isNotBlank()) {
                    val info = TheGamesDb.findByName(searchName, arcade = arcade)
                    if (info != null) {
                        info.imageUrl?.let { url ->
                            downloadImage(url, key, "TheGamesDB")?.let {
                                if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.d(TAG, "========== BOX ART DOWNLOAD END (TheGamesDB name) ==========")
                                return@fallback acceptOnlineCover(label, it)
                            }
                        }
                    }
                }
                missFile.writeText("not-found at ${System.currentTimeMillis()}")
                null
            },
            onRetry = { failure ->
                noteRetry(failure.retryAtMillis)
                if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.d(TAG, "Online cover postponed ($key): ${failure.message}")
            }
        )
    }

    /** Uses only library metadata and the bundled index; never opens a game image. */
    private fun downloadRepositoryCover(game: GameItem, key: String, style: Int, isLight: Boolean): String? {
        if (isSegaGame(game)) {
            val platform = catalogPlatformName(gamePlatform(game))
            val urls = listOfNotNull(
                coverIndex.findCoverBySerial(game.serial, style, isLight, platform),
                coverIndex.findSegaCover(game.title, game.fileName, platform, style, isLight)
            ).distinct()
            for (url in urls) downloadImage(url, key, "EmuCoreH Sega covers")?.let { return it }
            return null
        }
        val arcade = isArcadeGame(game.fileName)
        val title = FlycastBoxartSource.searchName(game.title, arcade)
        val urls = listOfNotNull(
            if (arcade) coverIndex.findArcadeCover(game.fileName, game.serial, style, isLight)
            else coverIndex.findCoverBySerial(game.serial, style, isLight),
            coverIndex.findCoverByTitle(title, style, isLight, allowFuzzy = false)
        ).distinct()
        for (url in urls) downloadImage(url, key, "EmuCoreH-Covers")?.let { return it }
        return null
    }

    /** Retain disc artwork for offline fallback after a style or theme change. */
    private fun acceptOnlineCover(previousCover: File?, downloadedPath: String): String {
        if (previousCover != null && cache.isOnline(previousCover) && previousCover.absolutePath != downloadedPath) {
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
        if (!extractCover) discMetadata[game.path]?.let { return it }
        if (isArcadeGame(game.fileName)) return null
        // Only disc images: playlists and other library content have no
        // ISO filesystem to scrape.
        val extension = game.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in GameFormats.discExtensions) return null
        return DiscScrapeAccess.read(
            game.path,
            prepareSaf = { SafStorageBridge.prepare(context, it) },
            resolveLocal = { runCatching { DocumentPathResolver.resolveFilePath(context, it) }.getOrNull() },
            scrape = scrape@{ nativePath ->
                var metadata: String? = null
                val cover = if (extractCover) {
                    cache.storeOffline(key) { output ->
                        metadata = NativeApp.scrapeDisc(nativePath, output.absolutePath)
                    }
                } else {
                    metadata = NativeApp.scrapeDisc(nativePath, null)
                    null
                }
                val fields = metadata?.split('\n') ?: return@scrape null
                DiscScrapeResult(
                    coverPath = cover?.absolutePath,
                    searchName = fields.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() },
                    uniqueId = fields.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                ).also { discMetadata[game.path] = it }
            }
        )
    }

    private fun extractDiscBoxart(game: GameItem, key: String): String? {
        if (isArcadeGame(game.fileName)) return null
        val bitmap = DiscGdtexExtractor.extractBitmap(context, game.path, game.fileName) ?: return null
        return runCatching {
            cache.storeOffline(key) { target ->
                target.outputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
            }?.absolutePath
        }.getOrElse { error ->
            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.w(TAG, "Unable to store GD-ROM texture: ${error.message}")
            null
        }
    }

    /** Public artwork URL of a cached cover, used for shareable metadata. */
    fun buildPublicCoverUrl(serial: String?, title: String? = null, platform: String? = null): String? {
        val prefs = AppPreferences(context)
        val style = prefs.getCoverArtStyleSync()
        val isLight = prefs.isThemeLightSync()
        val canonical = catalogPlatformName(platform)
        val indexUrl = coverIndex.findCoverBySerial(serial, style, isLight, canonical)
            ?: coverIndex.findCoverByTitle(title, style, isLight, platform = canonical)
            ?: (if (canonical == "Dreamcast") coverIndex.findArcadeCover(title, serial, style, isLight) else null)
        if (indexUrl != null) return indexUrl

        for (key in coverKeys(serial, title, platform)) {
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
        sourceName: String,
        mute: Boolean = false
    ): String? {
        if (urlString in missingImageUrls) return null
        return imageDownloader.download(urlString, key).also { path ->
            if (path != null) {
                if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.d(TAG, "$sourceName: SUCCESS - $path")
            } else {
                missingImageUrls.add(urlString)
                if (!mute) if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.d(TAG, "$sourceName: No artwork at $urlString")
            }
        }
    }
    private fun findCoverFile(key: String): File? {
        val prefs = AppPreferences(context)
        val style = prefs.getCoverArtStyleSync()
        val isLight = prefs.isThemeLightSync()
        return cache.find(listOf(key), style, isLight)
    }

    private fun isArcadeGame(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in GameFormats.romExtensions
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
            .also { if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.d(TAG, "Normalized: '$serial' -> '$it'") }
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
