package com.sbro.emucoreh.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.sbro.emucoreh.core.BiosValidator
import com.sbro.emucoreh.core.CrashLogger
import com.sbro.emucoreh.core.DocumentPathResolver
import com.sbro.emucoreh.core.EmulatorBridge
import com.sbro.emucoreh.core.SetupValidator
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.core.Model3Romsets
import com.sbro.emucoreh.core.SegaPlatformDetector
import com.sbro.emucoreh.data.catalog.ArcadeCatalogRepository
import com.sbro.emucoreh.data.catalog.TitleIndexRepository
import com.sbro.emucoreh.data.boxart.CoverFetchMode
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

data class GameDisc(
    val path: String,
    val fileName: String,
    val number: Int,
    val serial: String?,
    val fileSize: Long,
    val lastModified: Long,
    val discSize: Long? = null
)

data class GameItem(
    val title: String,
    val path: String,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Long,
    val coverArtPath: String? = null,
    val serial: String? = null,
    /** Total size of every track a multi-file disc image references. */
    val discSize: Long? = null,
    val discs: List<GameDisc> = emptyList(),
    val platform: String? = null
)

/**
 * Tells the caller whether a library scan actually finished. An interrupted or
 * truncated walk must never be cached as if it were the complete library: that
 * is how a general folder ends up showing only the games of the first
 * subfolder until the next rescan.
 */
class ScanOutcome {
    /** The walk stopped because the caller asked to abort (VM active, new request). */
    @Volatile
    var aborted: Boolean = false

    /** The walk hit the entry/depth guard before it finished the tree. */
    @Volatile
    var truncated: Boolean = false

    /** Entries the provider failed to describe; the rest of the tree still scanned. */
    @Volatile
    var unreadableEntries: Int = 0

    val complete: Boolean get() = !aborted && !truncated
}

class GameRepository {

    companion object {
        private const val TAG = "GameRepository"
        // Keep the library honest with what the bundled Flycast core mounts.
        private val COVER_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val COVER_DIRECTORY_NAMES = setOf("covers", "cover", "art", "artwork", "boxart", "box art")
        private const val MAX_DOCUMENT_SCAN_DEPTH = 32
        // Generous enough for a full library (a disc set contributes one entry
        // per track) while still bounding a pathological provider tree. Hitting
        // it marks the scan incomplete instead of silently caching a partial
        // library.
        private const val MAX_DOCUMENT_SCAN_ENTRIES = 200_000
        // CUE/GDI/M3U descriptors are a few kilobytes at most.
        private const val MAX_CONTAINER_BYTES = 1L * 1024L * 1024L

        private val genericDiscStems = setOf(
            "cd", "cdrom", "disc", "disk", "game", "gdrom", "gd-rom", "image"
        )
        private val genericFolderNames = setOf(
            "download", "downloads", "emulatorgames", "game", "games",
            "library", "rom", "roms"
        )
        private val trackStem = Regex("""track\d*""")
        private val nonAlphanumeric = Regex("[^a-z0-9]+")

        internal fun isStandaloneSegaBin(name: String, platform: EmulationPlatform, referencedByCue: Boolean): Boolean =
            name.endsWith(".bin", true) && ((platform.isSega && !platform.supportsDisc) || platform.isSaturn) && !referencedByCue

        /**
         * A folder-per-game dump usually names its descriptor `disc.gdi` or
         * `game.cue`. The file name says nothing about the game, so the
         * containing folder becomes the title instead. Platform folders such
         * as `atomiswave` or `games` are never used as a title.
         */
        internal fun genericDiscFolderTitle(fileName: String, parentName: String?): String? {
            val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (extension !in com.sbro.emucoreh.core.GameFormats.discExtensions) return null
            val stem = fileName.substringBeforeLast('.').trim().lowercase(Locale.ROOT)
            val generic = stem in genericDiscStems || stem.matches(trackStem)
            if (!generic) return null
            val parent = parentName?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val parentKey = parent.lowercase(Locale.ROOT).replace(nonAlphanumeric, "")
            if (parentKey in genericFolderNames) return null
            if (LibraryPlatformFolders.detectName(parent) != null) return null
            return parent
        }

        /** True while a metadata title only repeats the generic file name. */
        internal fun shouldUseFolderTitle(title: String?, fileName: String): Boolean {
            val normalized = title?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (normalized.isEmpty()) return true
            val stem = fileName.substringBeforeLast('.').trim().lowercase(Locale.ROOT)
            return normalized == stem || normalized == fileName.trim().lowercase(Locale.ROOT)
        }

        /**
         * Disc images remain on the Flycast core, but the library can still
         * group Naomi/Atomiswave ports by the folder the user keeps them in.
         * Archives are deliberately excluded: their platform comes from the
         * bundled romset catalog, not from a directory name.
         */
        internal fun folderPlatformFor(
            fileName: String,
            folder: LibraryPlatform?
        ): LibraryPlatform? {
            if (folder == null) return null
            val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return folder.takeIf {
                extension in com.sbro.emucoreh.core.GameFormats.discExtensions ||
                    extension in com.sbro.emucoreh.core.GameFormats.playlistExtensions
            }
        }

        private fun storedPlatform(platform: EmulationPlatform, name: String): String? = when {
            platform.coreId != 0 -> platform.name
            name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("chd", "cue", "gdi", "cdi", "iso", "m3u") -> platform.name
            else -> null // Arcade archives still need their Naomi/Atomiswave catalog classification.
        }
        internal fun libraryIdentity(path: String): String {
            if (!path.startsWith("content://")) {
                return runCatching { File(path).canonicalPath }
                    .getOrDefault(File(path).absolutePath)
            }
            return runCatching {
                val uri = path.toUri()
                val documentId = DocumentsContract.getDocumentId(uri)
                "${uri.authority}:$documentId"
            }.getOrDefault(path)
        }

        internal fun parentDocumentId(documentId: String): String? {
            val separatorIndex = documentId.lastIndexOf('/')
            if (separatorIndex >= 0) return documentId.substring(0, separatorIndex)

            val volumeSeparatorIndex = documentId.indexOf(':')
            return if (volumeSeparatorIndex >= 0 && volumeSeparatorIndex < documentId.lastIndex) {
                documentId.substring(0, volumeSeparatorIndex + 1)
            } else {
                null
            }
        }

        internal fun relativeDocumentSegments(rootDocumentId: String, targetDocumentId: String): List<String>? {
            if (targetDocumentId == rootDocumentId) return emptyList()
            val prefix = if (rootDocumentId.endsWith(':')) rootDocumentId else "$rootDocumentId/"
            if (!targetDocumentId.startsWith(prefix)) return null
            return targetDocumentId.removePrefix(prefix).split('/').filter(String::isNotBlank)
        }
    }

    fun scanDirectory(path: String, context: Context): List<GameItem> {
        return scanDirectory(path, context, emptyMap())
    }

    fun scanDirectory(
        path: String,
        context: Context,
        cachedGamesByPath: Map<String, GameItem>,
        shouldAbort: () -> Boolean = { false },
        preferEnglishTitles: Boolean = false,
        outcome: ScanOutcome = ScanOutcome()
    ): List<GameItem> {
        val startedNanos = System.nanoTime()
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val scan = ScanEnvironment.create(context, preferEnglishTitles)
        val discovered = scanLocalDirectory(
            dir,
            scan,
            cachedGamesByPath,
            shouldAbort,
            outcome = outcome
        ).distinctBy { libraryIdentity(it.path) }
        val scanned = DiscTrackReferences.withoutReferencedTracks(discovered, scan.referencedTracks) { game ->
            dir.toPath().relativize(File(game.path).toPath()).joinToString("/")
        }
        val games = groupLocalDiscs(scanned).sortedBy { it.title.lowercase() }
        logScanSummary("local", path, games, outcome, startedNanos)
        return games
    }

    fun scanDirectoryFromUri(uri: Uri, context: Context): List<GameItem> {
        return scanDirectoryFromUri(uri, context, emptyMap())
    }

    fun scanDirectoryFromUri(
        uri: Uri,
        context: Context,
        cachedGamesByPath: Map<String, GameItem>,
        shouldAbort: () -> Boolean = { false },
        preferEnglishTitles: Boolean = false,
        outcome: ScanOutcome = ScanOutcome()
    ): List<GameItem> {
        if (!DocumentsContract.isTreeUri(uri)) {
            return emptyList()
        }
        val startedNanos = System.nanoTime()
        return runCatching {
            val docFile = DocumentFile.fromTreeUri(context, uri) ?: return@runCatching emptyList()
            val locations = mutableMapOf<String, ScannedDocumentLocation>()
            val scan = ScanEnvironment.create(context, preferEnglishTitles)
            val discovered = scanDocumentFile(
                docFile,
                scan,
                cachedGamesByPath,
                shouldAbort,
                depth = 0,
                budget = DocumentScanBudget(),
                directorySegments = emptyList(),
                locations = locations,
                outcome = outcome
            ).distinctBy { libraryIdentity(it.path) }
            val scanned = DiscTrackReferences.withoutReferencedTracks(discovered, scan.referencedTracks) { game ->
                locations[game.path]?.segments?.joinToString("/").orEmpty()
            }
            val games = groupDocumentDiscs(scanned, locations, context).sortedBy { it.title.lowercase() }
            logScanSummary("document", uri.toString(), games, outcome, startedNanos)
            games
        }.onFailure { error ->
            // A provider failure must not look like an empty library: report it
            // and let the caller keep the previous snapshot instead of caching
            // an empty folder listing.
            outcome.aborted = true
            CrashLogger.logError(TAG, "Unable to scan document tree: $uri", error)
        }.getOrDefault(emptyList())
    }

    private fun logScanSummary(
        kind: String,
        root: String,
        games: List<GameItem>,
        outcome: ScanOutcome,
        startedNanos: Long
    ) {
        val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L
        val platforms = games.groupingBy { it.platform ?: "unclassified" }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(16)
            .joinToString(",") { "${it.key}=${it.value}" }
        CrashLogger.logStep(
            "Library",
            "scan_$kind root=${shortRoot(root)} games=${games.size} " +
                "complete=${outcome.complete} aborted=${outcome.aborted} " +
                "truncated=${outcome.truncated} unreadable=${outcome.unreadableEntries} " +
                "elapsedMs=$elapsedMs platforms=[$platforms]"
        )
    }

    private fun shortRoot(root: String): String =
        if (root.startsWith("content://")) DocumentPathResolver.normalizeDisplayName(root) else File(root).name

    fun findCoverForGame(
        path: String,
        context: Context,
        serial: String? = null,
        title: String? = null
    ): String? {
        val platform = SegaPlatformDetector.detect(context, path).takeIf { it.coreId != 0 }?.name
        val candidate = if (path.startsWith("content://")) {
            runCatching { findDocumentCover(path, context, serial, title, platform) }
                .onFailure { error -> if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.w(TAG, "Unable to find cover for document URI: $path", error) }
                .getOrNull()
        } else {
            findLocalCover(path, context, serial, title, platform)
        }
        return CoverArtRepository(context).displayCoverPath(serial, title, candidate, platform = platform)
    }

    fun downloadCoverForGame(
        game: GameItem,
        context: Context,
        covers: CoverArtRepository = CoverArtRepository(context),
        mode: CoverFetchMode = CoverFetchMode.FULL
    ): String? {
        CustomGameCoverRepository(context).findCustomCoverPath(game.path)?.let { return it }
        // Prefer our cover catalog without opening an image; retain Flycast's
        // disc artwork and external sources as fallbacks.
        covers.downloadCover(game, mode)?.let { return it }
        covers.findCachedCoverPath(game.serial, title = game.title, platform = game.platform)?.let { return it }
        return game.coverArtPath?.takeIf { File(it).isFile }
    }

    private fun scanLocalDirectory(
        dir: File,
        scan: ScanEnvironment,
        cachedGamesByPath: Map<String, GameItem>,
        shouldAbort: () -> Boolean,
        inheritedPlatform: LibraryPlatform? = null,
        directorySegments: List<String> = emptyList(),
        outcome: ScanOutcome = ScanOutcome()
    ): List<GameItem> {
        val items = mutableListOf<GameItem>()
        val children = dir.listFiles().orEmpty()
        val directoryPlatform = LibraryPlatformFolders.detectName(dir.name) ?: inheritedPlatform
        val siblingsByName = children
            .filter { it.isFile }
            .associateBy { it.name.lowercase(Locale.US) }
        // Keep root-relative references until traversal completes: descriptors
        // can reference tracks in child directories or an earlier sibling.
        scan.referencedTracks += DiscTrackReferences.referenced(
            children.asSequence()
                .filter {
                    it.isFile && DiscTrackReferences.isDescriptor(it.name) &&
                        it.length() <= MAX_CONTAINER_BYTES
                }
                .takeWhile { !shouldAbort() }
                .map {
                    (directorySegments + it.name).joinToString("/") to
                        runCatching { it.inputStream().use { stream -> stream.readPlaylistText() } }.getOrNull().orEmpty()
                }
        )
        val coverCandidates = buildLocalCoverCandidates(children)

        children.forEach { file ->
            if (shouldAbort()) {
                outcome.aborted = true
                return items
            }
            when {
                file.isDirectory && normalizeBaseName(file.name) !in COVER_DIRECTORY_NAMES -> {
                    items.addAll(
                        scanLocalDirectory(
                            file,
                            scan,
                            cachedGamesByPath,
                            shouldAbort,
                            directoryPlatform,
                            directorySegments + file.name,
                            outcome
                        )
                    )
                }

                file.isFile && (com.sbro.emucoreh.core.GameFormats.isSupportedName(file.name) || file.extension.equals("bin", true)) -> {
                    val platform = SegaPlatformDetector.detectFile(file)
                    if (file.extension.equals("bin", true) && !isStandaloneSegaBin(file.name, platform, false)) return@forEach
                    if (!isLibraryContent(file.name, scan.arcadeCatalog, platform)) return@forEach
                    val entrySize = file.length()
                    val lastModified = file.lastModified()
                    val cachedGame = cachedGamesByPath[file.absolutePath]
                    val folderTitle = genericDiscFolderTitle(file.name, dir.name)
                    val folderPlatform = folderPlatformFor(file.name, directoryPlatform)
                        ?.takeIf { platform.coreId == 0 }
                    val cachedTitleIsGeneric = folderTitle != null && cachedGame != null &&
                        shouldUseFolderTitle(cachedGame.title, file.name)
                    val cachedPlatformIsStale = cachedGame != null && folderPlatform != null &&
                        folderPlatform != LibraryPlatform.DREAMCAST &&
                        cachedGame.platform != folderPlatform.name
                    val canReuseCachedMetadata = !platform.isSega && cachedGame != null &&
                        cachedGame.fileSize == entrySize &&
                        cachedGame.lastModified == lastModified &&
                        cachedGame.fileName == file.name &&
                        !cachedGame.serial.isNullOrBlank() &&
                        !cachedTitleIsGeneric &&
                        !cachedPlatformIsStale
                    val metadata = if (canReuseCachedMetadata) {
                        com.sbro.emucoreh.core.GameMetadata(cachedGame.title, cachedGame.serial)
                    } else {
                        val sourcePath = file.absolutePath
                        val displayName = folderTitle ?: file.name
                        // A single unreadable ROM must not abort the whole library walk.
                        runCatching {
                            readLibraryScanMetadata(
                                platform = platform,
                                inferFromName = { EmulatorBridge.parseMetadataFromName(displayName) },
                                readPlatformMetadata = {
                                    EmulatorBridge.getGameMetadata(
                                        sourcePath,
                                        readDiscMetadata = false,
                                        knownPlatform = platform,
                                        knownDisplayName = displayName
                                    )
                                },
                                learnedSerial = { scan.learnedSerials[LearnedSerialRepository.key(file.name)] }
                            )
                        }.getOrElse { error ->
                            outcome.unreadableEntries++
                            if (com.sbro.emucoreh.BuildConfig.DEBUG) {
                                Log.w(TAG, "Metadata read failed for ${file.name}", error)
                            }
                            com.sbro.emucoreh.core.GameMetadata(displayName, null)
                        }
                    }
                    if (BiosValidator.isLikelyBiosLibraryEntry(file.name, metadata.title, metadata.serial)) {
                        return@forEach
                    }

                    val displayName = folderTitle ?: file.name
                    val cleanTitle = EmulatorBridge.cleanGameDisplayTitle(metadata.title, displayName)
                    val serial = metadata.serial
                    val title = when {
                        folderTitle != null && shouldUseFolderTitle(metadata.title, file.name) ->
                            EmulatorBridge.cleanGameDisplayTitle(folderTitle, file.name)
                        scan.preferEnglishTitles && !platform.isSega ->
                            scan.titleIndex.titleForSerial(serial) ?: cleanTitle
                        else -> cleanTitle
                    }
                    val libraryPlatform = folderPlatform
                    items += GameItem(
                        title = title,
                        path = file.absolutePath,
                        fileName = file.name,
                        fileSize = entrySize,
                        discSize = DiscSizeResolver.resolve(
                            entryName = file.name,
                            sizeOf = { name -> siblingsByName[name.lowercase(Locale.US)]?.length() },
                            readText = { name ->
                                siblingsByName[name.lowercase(Locale.US)]
                                    ?.takeIf { it.length() <= MAX_CONTAINER_BYTES }
                                    ?.let { runCatching { it.readText() }.getOrNull() }
                            }
                        ),
                        lastModified = lastModified,
                        coverArtPath = scan.customCoverRepository.findCustomCoverPath(file.absolutePath, scan.customCoverIndex)
                            ?: scan.coverRepository.findCachedCoverPath(
                                serial,
                                title = title,
                                ignoreDisabled = false,
                                platform = libraryPlatform?.name ?: platform.takeIf { it.coreId != 0 }?.name,
                                settings = scan.coverSettings
                            )
                            ?: cachedGame?.takeUnless { cachedTitleIsGeneric || cachedPlatformIsStale }
                                ?.coverArtPath?.takeIf { File(it).exists() }
                            ?: coverCandidates[normalizeBaseName(file.nameWithoutExtension)]?.absolutePath
                            ?: coverCandidates[normalizeBaseName(cleanGameName(title))]?.absolutePath,
                        serial = serial,
                        platform = libraryPlatform?.name ?: storedPlatform(platform, file.name)
                    )
                }
            }
        }

        return items
    }

    private fun scanDocumentFile(
        docFile: DocumentFile,
        scan: ScanEnvironment,
        cachedGamesByPath: Map<String, GameItem>,
        shouldAbort: () -> Boolean,
        depth: Int,
        budget: DocumentScanBudget,
        directorySegments: List<String>,
        locations: MutableMap<String, ScannedDocumentLocation>,
        inheritedPlatform: LibraryPlatform? = null,
        outcome: ScanOutcome = ScanOutcome()
    ): List<GameItem> {
        if (depth > MAX_DOCUMENT_SCAN_DEPTH) {
            outcome.truncated = true
            return emptyList()
        }
        if (!budget.tryEnterDirectory(docFile.uri.toString())) {
            return emptyList()
        }
        val context = scan.context
        val items = mutableListOf<GameItem>()
        val children = runCatching { docFile.listFiles() }.getOrDefault(emptyArray())
        // DocumentFile performs a separate provider query for every property
        // (name, MIME type, size, timestamp). Query the whole child list once so
        // a large SAF library does not turn into thousands of Binder round-trips.
        val childInfo = queryChildDocumentInfo(context, docFile)
        fun infoFor(file: DocumentFile): DocumentEntryInfo? = childInfo[file.uri.toString()]
        val directoryPlatform = LibraryPlatformFolders.detectName(
            runCatching { docFile.name }.getOrNull()
        ) ?: inheritedPlatform
        val siblingsByName = lazy {
            children.mapNotNull { child ->
                documentDisplayName(context, child, infoFor(child))
                    .lowercase(Locale.US)
                    .takeIf { it.isNotBlank() }
                    ?.let { it to child }
            }.toMap()
        }
        scan.referencedTracks += DiscTrackReferences.referenced(
            children.asSequence().takeWhile { !shouldAbort() }.mapNotNull { child ->
                val childName = documentDisplayName(context, child, infoFor(child))
                if (!DiscTrackReferences.isDescriptor(childName)) return@mapNotNull null
                val size = infoFor(child)?.size
                    ?: runCatching { child.length() }.getOrDefault(0L)
                if (size > MAX_CONTAINER_BYTES) return@mapNotNull null
                val text = runCatching {
                    context.contentResolver.openInputStream(child.uri)?.use { it.readPlaylistText() }
                }.getOrNull().orEmpty()
                (directorySegments + childName).joinToString("/") to text
            }
        )
        val coverCandidates = buildDocumentCoverCandidates(children, ::infoFor)

        for (file in children) {
            if (shouldAbort()) {
                outcome.aborted = true
                return items
            }
            if (!budget.tryVisitEntry()) {
                outcome.truncated = true
                return items
            }
            val info = infoFor(file)
            val mimeType = info?.mimeType ?: runCatching { file.type }.getOrNull()
            val name = documentDisplayName(context, file, info)
            val kind = if (name.endsWith(".bin", true)) SetupValidator.DocumentEntryKind.GAME_FILE else
                SetupValidator.classifyDocumentEntry(mimeType, name)
            when (kind) {
                SetupValidator.DocumentEntryKind.DIRECTORY,
                SetupValidator.DocumentEntryKind.UNKNOWN -> {
                    if (normalizeBaseName(name) !in COVER_DIRECTORY_NAMES) {
                        val nested = try {
                            scanDocumentFile(
                                file,
                                scan,
                                cachedGamesByPath,
                                shouldAbort,
                                depth + 1,
                                budget,
                                directorySegments + name,
                                locations,
                                directoryPlatform,
                                outcome
                            )
                        } catch (cancelled: java.util.concurrent.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            // One unreadable folder must not hide the rest of the tree.
                            outcome.unreadableEntries++
                            if (com.sbro.emucoreh.BuildConfig.DEBUG) {
                                Log.w(TAG, "Unable to scan directory ${file.uri}", error)
                            }
                            emptyList()
                        }
                        items.addAll(nested)
                    }
                }
                SetupValidator.DocumentEntryKind.GAME_FILE -> {
                    val uriPath = file.uri.toString()
                    val entrySize = info?.size?.takeIf { it >= 0L }
                        ?: runCatching { file.length() }.getOrDefault(0L)
                    val lastModified = info?.lastModified
                        ?: runCatching { file.lastModified() }.getOrDefault(0L)
                    val cachedGame = cachedGamesByPath[uriPath]
                    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    val folderTitle = genericDiscFolderTitle(
                        name,
                        directorySegments.lastOrNull() ?: runCatching { docFile.name }.getOrNull()
                    )
                    val folderPlatform = folderPlatformFor(name, directoryPlatform)
                    val cachedTitleIsGeneric = folderTitle != null && cachedGame != null &&
                        shouldUseFolderTitle(cachedGame.title, name)
                    val cachedPlatformIsStale = cachedGame != null && folderPlatform != null &&
                        folderPlatform != LibraryPlatform.DREAMCAST &&
                        cachedGame.platform != folderPlatform.name
                    val knownArcadeArchive = extension in com.sbro.emucoreh.core.GameFormats.archives &&
                        (Model3Romsets.find(name) != null || scan.arcadeCatalog.isKnownRomset(name))
                    // Unknown 7z archives are always filtered out below. Do not
                    // spend time opening them just to discover that.
                    if (extension == "7z" && !knownArcadeArchive) continue
                    // A matching cache entry already contains the expensive
                    // platform/header/disc metadata. Reusing it avoids opening
                    // every ROM and multi-gigabyte disc on each scan.
                    val canReuseCachedGame = cachedGame != null &&
                        cachedGame.fileSize == entrySize &&
                        cachedGame.lastModified == lastModified &&
                        cachedGame.fileName == name &&
                        !(cachedGame.platform == null && extension in setOf("chd", "cue", "m3u", "iso", "bin")) &&
                        !cachedTitleIsGeneric &&
                        !cachedPlatformIsStale
                    if (canReuseCachedGame) {
                        locations[uriPath] = ScannedDocumentLocation(
                            document = file,
                            parentIdentity = libraryIdentity(docFile.uri.toString()),
                            segments = directorySegments + name
                        )
                        items += cachedGame
                        continue
                    }
                    // Known arcade archives are classified by the catalog; opening
                    // (and inflating) them would only repeat that work.
                    val platform = when {
                        knownArcadeArchive && Model3Romsets.find(name) != null -> EmulationPlatform.MODEL_3
                        knownArcadeArchive -> EmulationPlatform.DREAMCAST
                        else -> SegaPlatformDetector.detect(context, uriPath, knownDisplayName = name)
                    }
                    if (name.endsWith(".bin", true) && !isStandaloneSegaBin(name, platform, false)) continue
                    if (!isLibraryContent(name, scan.arcadeCatalog, platform)) continue
                    val libraryPlatform = folderPlatform?.takeIf { platform.coreId == 0 }
                    val displayName = folderTitle ?: name
                    // A single unreadable document must not abort the whole library walk.
                    val metadata = runCatching {
                        readLibraryScanMetadata(
                            platform = platform,
                            inferFromName = { EmulatorBridge.parseMetadataFromName(displayName) },
                            readPlatformMetadata = {
                                EmulatorBridge.getGameMetadata(
                                    uriPath,
                                    readDiscMetadata = false,
                                    knownPlatform = platform,
                                    knownDisplayName = displayName
                                )
                            },
                            learnedSerial = { scan.learnedSerials[LearnedSerialRepository.key(name)] }
                        )
                    }.getOrElse { error ->
                        outcome.unreadableEntries++
                        if (com.sbro.emucoreh.BuildConfig.DEBUG) {
                            Log.w(TAG, "Metadata read failed for $name", error)
                        }
                        com.sbro.emucoreh.core.GameMetadata(displayName, null)
                    }

                    if (BiosValidator.isLikelyBiosLibraryEntry(name, metadata.title, metadata.serial)) {
                        continue
                    }

                    val cleanTitle = cleanScannedTitle(metadata.title, displayName)
                    val serial = metadata.serial
                    val title = when {
                        folderTitle != null && shouldUseFolderTitle(metadata.title, name) ->
                            EmulatorBridge.cleanGameDisplayTitle(folderTitle, name)
                        scan.preferEnglishTitles && !platform.isSega ->
                            scan.titleIndex.titleForSerial(serial) ?: cleanTitle
                        else -> cleanTitle
                    }
                    locations[uriPath] = ScannedDocumentLocation(
                        document = file,
                        parentIdentity = libraryIdentity(docFile.uri.toString()),
                        segments = directorySegments + name
                    )
                    items += GameItem(
                        title = title,
                        path = uriPath,
                        fileName = name,
                        fileSize = entrySize,
                        discSize = DiscSizeResolver.resolve(
                            entryName = name,
                            sizeOf = { siblingName ->
                                val sibling = siblingsByName.value[siblingName.lowercase(Locale.US)]
                                sibling?.let { child ->
                                    infoFor(child)?.size?.takeIf { it > 0L }
                                        ?: runCatching { child.length() }.getOrNull()?.takeIf { it > 0L }
                                }
                            },
                            readText = { siblingName ->
                                siblingsByName.value[siblingName.lowercase(Locale.US)]
                                    ?.takeIf {
                                        val size = infoFor(it)?.size
                                            ?: runCatching { it.length() }.getOrDefault(0L)
                                        size <= MAX_CONTAINER_BYTES
                                    }
                                    ?.let { sibling ->
                                        runCatching {
                                            context.contentResolver.openInputStream(sibling.uri)?.use { stream ->
                                                stream.readPlaylistText()
                                            }
                                        }.getOrNull()
                                    }
                            }
                        ),
                        lastModified = lastModified,
                        coverArtPath = scan.customCoverRepository.findCustomCoverPath(uriPath, scan.customCoverIndex)
                            ?: scan.coverRepository.findCachedCoverUri(
                                serial,
                                title = title,
                                platform = libraryPlatform?.name ?: platform.takeIf { it.coreId != 0 }?.name,
                                settings = scan.coverSettings
                            )
                            ?: cachedGame?.takeUnless { cachedTitleIsGeneric || cachedPlatformIsStale }
                                ?.coverArtPath
                            ?: coverCandidates[normalizeBaseName(name.substringBeforeLast('.'))]?.uri?.toString()
                            ?: coverCandidates[normalizeBaseName(cleanGameName(title))]?.uri?.toString(),
                        serial = serial,
                        platform = libraryPlatform?.name ?: storedPlatform(platform, name)
                    )
                }
                SetupValidator.DocumentEntryKind.OTHER -> Unit
            }
        }

        return items
    }

    private fun groupLocalDiscs(games: List<GameItem>): List<GameItem> {
        val byIdentity = games.associateBy { libraryIdentity(it.path) }
        val playlists = games.filter { it.fileName.endsWith(".m3u", ignoreCase = true) }
            .mapNotNull { playlist ->
                val file = File(playlist.path)
                val text = readPlaylistFile(file) ?: return@mapNotNull null
                val references = MultiDiscLibrary.parsePlaylist(text)
                val members = references.map { reference ->
                    // A local playlist may use ../ within the scanned library. Canonical
                    // lookup below only accepts actual files found by this scan.
                    if (!isRelativePlaylistReference(reference)) null else runCatching {
                        val target = File(file.parentFile, reference.replace('\\', '/'))
                        byIdentity[libraryIdentity(target.path)]?.path?.takeIf { target.canRead() }
                    }.getOrNull()
                }
                if (members.any { it == null }) null else playlist.path to members.filterNotNull()
            }.toMap()
        val parents = games.associate { game ->
            game.path to runCatching { File(game.path).canonicalFile.parentFile?.path }.getOrNull().orEmpty()
        }
        return MultiDiscLibrary.group(games, playlists, parents)
    }

    private fun groupDocumentDiscs(
        games: List<GameItem>,
        locations: Map<String, ScannedDocumentLocation>,
        context: Context
    ): List<GameItem> {
        val bySegments = games.mapNotNull { game ->
            locations[game.path]?.segments?.let { it to game.path }
        }.groupBy({ it.first }, { it.second })
        val playlists = games.filter { it.fileName.endsWith(".m3u", ignoreCase = true) }
            .mapNotNull { playlist ->
                val location = locations[playlist.path] ?: return@mapNotNull null
                val text = readPlaylistDocument(location.document, context) ?: return@mapNotNull null
                val members = MultiDiscLibrary.parsePlaylist(text).map { reference ->
                    val segments = MultiDiscLibrary.resolveRelativeSegments(location.segments.dropLast(1), reference)
                        ?: return@map null
                    bySegments[segments]?.singleOrNull()?.takeIf { path ->
                        locations[path]?.document?.canRead() == true
                    }
                }
                if (members.any { it == null }) null else playlist.path to members.filterNotNull()
            }.toMap()
        return MultiDiscLibrary.group(
            games, playlists, locations.mapValues { it.value.parentIdentity }
        )
    }

    private fun isRelativePlaylistReference(reference: String): Boolean {
        val normalized = reference.replace('\\', '/')
        return normalized.isNotBlank() && !normalized.startsWith('/') &&
            !Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(normalized)
    }

    private fun readPlaylistFile(file: File): String? = runCatching {
        if (file.length() > MAX_CONTAINER_BYTES) return@runCatching null
        file.inputStream().use { it.readPlaylistText() }
    }.getOrNull()

    private fun readPlaylistDocument(document: DocumentFile, context: Context): String? = runCatching {
        if (document.length() > MAX_CONTAINER_BYTES) return@runCatching null
        context.contentResolver.openInputStream(document.uri)?.use { it.readPlaylistText() }
    }.getOrNull()

    private fun InputStream.readPlaylistText(): String? {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (result.size().toLong() + count > MAX_CONTAINER_BYTES) return null
            result.write(buffer, 0, count)
        }
        return result.toString(Charsets.UTF_8.name())
    }

    private data class ScannedDocumentLocation(
        val document: DocumentFile,
        val parentIdentity: String,
        val segments: List<String>
    )

    private data class DocumentEntryInfo(
        val name: String?,
        val mimeType: String?,
        val size: Long?,
        val lastModified: Long?
    )

    /**
     * Query all child properties in one provider call. [DocumentFile] resolves
     * name, MIME type, size and timestamp with separate Binder requests, which
     * is prohibitive while walking a large SAF tree on the first launch.
     */
    private fun queryChildDocumentInfo(
        context: Context,
        directory: DocumentFile
    ): Map<String, DocumentEntryInfo> {
        val directoryUri = directory.uri
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                directoryUri,
                DocumentsContract.getDocumentId(directoryUri)
            )
        }.getOrNull() ?: return emptyMap()

        return runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                if (idIndex < 0) return@use emptyMap<String, DocumentEntryInfo>()
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val entries = HashMap<String, DocumentEntryInfo>(cursor.count)
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(idIndex) ?: continue
                    val childUri = runCatching {
                        DocumentsContract.buildDocumentUriUsingTree(directoryUri, documentId)
                    }.getOrNull() ?: continue
                    entries[childUri.toString()] = DocumentEntryInfo(
                        name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                            cursor.getString(nameIndex)
                        } else null,
                        mimeType = if (mimeIndex >= 0 && !cursor.isNull(mimeIndex)) {
                            cursor.getString(mimeIndex)
                        } else null,
                        size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            cursor.getLong(sizeIndex)
                        } else null,
                        lastModified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                            cursor.getLong(modifiedIndex)
                        } else null
                    )
                }
                entries
            } ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    private class ScanEnvironment(
        val context: Context,
        val coverRepository: CoverArtRepository,
        val customCoverRepository: CustomGameCoverRepository,
        val customCoverIndex: Map<String, String>,
        val coverSettings: CoverDisplaySettings,
        val titleIndex: TitleIndexRepository,
        val arcadeCatalog: ArcadeCatalogRepository,
        val learnedSerials: Map<String, String>,
        val preferEnglishTitles: Boolean
    ) {
        val referencedTracks = HashSet<String>()

        companion object {
            fun create(context: Context, preferEnglishTitles: Boolean): ScanEnvironment {
                val appContext = context.applicationContext
                val coverRepository = CoverArtRepository(appContext)
                val customCoverRepository = CustomGameCoverRepository(appContext)
                return ScanEnvironment(
                    context = appContext,
                    coverRepository = coverRepository,
                    customCoverRepository = customCoverRepository,
                    customCoverIndex = customCoverRepository.customCoverIndex(),
                    coverSettings = coverRepository.displayCoverSettings(),
                    titleIndex = TitleIndexRepository(appContext),
                    arcadeCatalog = ArcadeCatalogRepository(appContext),
                    learnedSerials = LearnedSerialRepository.forContext(appContext).all(),
                    preferEnglishTitles = preferEnglishTitles
                )
            }
        }
    }

    private fun findLocalCover(path: String, context: Context, serial: String?, title: String?, platform: String? = null): String? {
        val file = File(path)
        val parent = file.parentFile ?: return null
        val baseName = normalizeBaseName(file.nameWithoutExtension)
        val titleKey = normalizeBaseName(cleanGameName(title ?: EmulatorBridge.getGameTitle(path)))
        val coverCandidates = buildLocalCoverCandidates(parent.listFiles().orEmpty())
        return CustomGameCoverRepository(context).findCustomCoverPath(path)
            ?: CoverArtRepository(context).findCachedCoverPath(serial, title = title, platform = platform)
            ?: coverCandidates[baseName]?.absolutePath
            ?: coverCandidates[titleKey]?.absolutePath
    }

    private fun findDocumentCover(path: String, context: Context, serial: String?, title: String?, platform: String? = null): String? {
        CustomGameCoverRepository(context).findCustomCoverPath(path)?.let { return it }
        CoverArtRepository(context).findCachedCoverUri(serial, title = title, platform = platform)?.let { return it }

        val uri = path.toUri()
        val document = DocumentFile.fromSingleUri(context, uri) ?: return null
        val parent = findDocumentParent(context, uri) ?: return null
        val baseName = normalizeBaseName(document.name.orEmpty().substringBeforeLast('.'))
        val titleKey = normalizeBaseName(cleanGameName(title ?: document.name.orEmpty().substringBeforeLast('.')))
        val coverCandidates = buildDocumentCoverCandidates(parent.listFiles())
        return coverCandidates[baseName]?.uri?.toString()
            ?: coverCandidates[titleKey]?.uri?.toString()
    }

    private fun findDocumentParent(context: Context, documentUri: Uri): DocumentFile? {
        val documentId = DocumentsContract.getDocumentId(documentUri)
        val parentId = parentDocumentId(documentId) ?: return null
        val persistedTrees = context.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { permission -> permission.isReadPermission && permission.uri.authority == documentUri.authority }
            .mapNotNull { permission ->
                val rootId = runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull()
                    ?: return@mapNotNull null
                Triple(permission.uri, rootId, relativeDocumentSegments(rootId, parentId))
            }
            .filter { (_, _, segments) -> segments != null }
            .sortedByDescending { (_, rootId, _) -> rootId.length }
            .toList()

        for ((treeUri, _, nullableSegments) in persistedTrees) {
            var current = DocumentFile.fromTreeUri(context, treeUri) ?: continue
            var found = true
            for (segment in nullableSegments.orEmpty()) {
                current = current.findFile(segment) ?: run {
                    found = false
                    break
                }
            }
            if (found) return current
        }

        return null
    }

    /**
     * Mirrors Flycast's content scanner: `.zip`/`.7z` archives are only listed
     * when their base name is a known Naomi/Naomi 2/Atomiswave romset, and raw
     * arcade GD-ROM images are hidden because the core boots them through the
     * romset archive. Legacy `.dat`/`.lst` sets and disc formats stay visible;
     * the core rejects archives it does not know, so listing them as games
     * would only produce broken entries.
     */
    private fun isLibraryContent(name: String, arcadeCatalog: ArcadeCatalogRepository, platform: EmulationPlatform): Boolean {
        if (platform.coreId != 0) return true
        val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (extension) {
            in com.sbro.emucoreh.core.GameFormats.archives -> {
                val known = arcadeCatalog.isKnownRomset(name)
                if (!known) {
                    // Diagnostic for arcade reports: logcat shows which
                    // archives the Flycast game table does not recognise.
                    if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.d(TAG, "Skipping unrecognised archive: $name")
                }
                known
            }
            "chd", "gdi" -> !arcadeCatalog.isArcadeGdrom(name)
            else -> true
        }
    }

    private fun normalizeBaseName(value: String): String {
        return value.lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("""\[[^]]*]|\([^)]*\)"""), " ")
            .replace(Regex("""\b(disc|disk|cd|dvd)\s*[0-9]+\b"""), " ")
            .replace(Regex("""\b\(?(usa|eur|europe|japan|jpn|beta|demo|proto|prototype|rev\s*[a-z0-9]+)\)?\b"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanGameName(value: String): String {
        return value.substringBeforeLast('.')
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun cleanScannedTitle(rawTitle: String, displayName: String): String {
        return EmulatorBridge.cleanGameDisplayTitle(rawTitle, displayName)
    }

    private fun buildLocalCoverCandidates(children: Array<out File>): Map<String, File> {
        val directFiles = children.filter { it.isFile && it.extension.lowercase() in COVER_EXTENSIONS }
        val nestedCoverFiles = children
            .filter { it.isDirectory && normalizeBaseName(it.name) in COVER_DIRECTORY_NAMES }
            .flatMap { it.listFiles().orEmpty().asIterable() }
            .filter { it.isFile && it.extension.lowercase() in COVER_EXTENSIONS }

        return (directFiles + nestedCoverFiles).associateBy { normalizeBaseName(cleanGameName(it.nameWithoutExtension)) }
    }

    private fun buildDocumentCoverCandidates(
        children: Array<DocumentFile>,
        infoFor: ((DocumentFile) -> DocumentEntryInfo?)? = null
    ): Map<String, DocumentFile> {
        fun typeOf(file: DocumentFile): String? =
            infoFor?.invoke(file)?.mimeType ?: runCatching { file.type }.getOrNull()

        fun nameOf(file: DocumentFile): String =
            documentDisplayName(null, file, infoFor?.invoke(file))

        val directFiles = children
            .filter {
                typeOf(it) != DocumentsContract.Document.MIME_TYPE_DIR &&
                    nameOf(it)
                        .substringAfterLast('.', "")
                        .lowercase() in COVER_EXTENSIONS
            }
        val nestedCoverFiles = children
            .filter {
                val type = typeOf(it)
                (type == DocumentsContract.Document.MIME_TYPE_DIR || type == null) &&
                    normalizeBaseName(nameOf(it)) in COVER_DIRECTORY_NAMES
            }
            .flatMap { runCatching { it.listFiles().asIterable() }.getOrDefault(emptyList()) }
            .filter {
                runCatching { it.type }.getOrNull() != DocumentsContract.Document.MIME_TYPE_DIR &&
                    documentDisplayName(null, it)
                        .substringAfterLast('.', "")
                        .lowercase() in COVER_EXTENSIONS
            }

        return (directFiles + nestedCoverFiles).associateBy {
            normalizeBaseName(cleanGameName(nameOf(it).substringBeforeLast('.')))
        }
    }

    private fun documentDisplayName(
        context: Context?,
        document: DocumentFile,
        info: DocumentEntryInfo? = null
    ): String {
        val rawName = info?.name?.takeIf { it.isNotBlank() }
            ?: runCatching { document.name }.getOrNull().orEmpty()
        if (rawName.isNotBlank()) return DocumentPathResolver.normalizeDisplayName(rawName)
        if (context == null) return ""
        return runCatching { DocumentPathResolver.getDisplayName(context, document.uri.toString()) }
            .getOrDefault("")
    }

    private class DocumentScanBudget {
        private var visitedEntries = 0
        private val visitedDirectories = HashSet<String>()

        fun tryEnterDirectory(uri: String): Boolean = visitedDirectories.add(uri)

        fun tryVisitEntry(): Boolean {
            if (visitedEntries >= MAX_DOCUMENT_SCAN_ENTRIES) return false
            visitedEntries++
            return true
        }
    }
}
