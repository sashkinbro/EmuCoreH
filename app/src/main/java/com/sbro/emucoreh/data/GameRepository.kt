package com.sbro.emucoreh.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.sbro.emucoreh.core.BiosValidator
import com.sbro.emucoreh.core.DocumentPathResolver
import com.sbro.emucoreh.core.EmulatorBridge
import com.sbro.emucoreh.core.SetupValidator
import com.sbro.emucoreh.core.GameMetadataReader
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.core.SegaPlatformDetector
import com.sbro.emucoreh.core.JGenesisContentPreparer
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

class GameRepository {

    companion object {
        private const val TAG = "GameRepository"
        // Keep the library honest with what the bundled Flycast core mounts.
        private val COVER_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val COVER_DIRECTORY_NAMES = setOf("covers", "cover", "art", "artwork", "boxart", "box art")
        private const val MAX_DOCUMENT_SCAN_DEPTH = 32
        private const val MAX_DOCUMENT_SCAN_ENTRIES = 20_000
        // CUE/GDI/M3U descriptors are a few kilobytes at most.
        private const val MAX_CONTAINER_BYTES = 1L * 1024L * 1024L

        internal fun isStandaloneSegaBin(name: String, platform: EmulationPlatform, referencedByCue: Boolean): Boolean =
            name.endsWith(".bin", true) && platform.isSega && !platform.supportsDisc && !referencedByCue
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
        preferEnglishTitles: Boolean = false
    ): List<GameItem> {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val scanned = scanLocalDirectory(
            dir,
            context,
            cachedGamesByPath,
            shouldAbort,
            preferEnglishTitles
        ).distinctBy { libraryIdentity(it.path) }
        return groupLocalDiscs(scanned).sortedBy { it.title.lowercase() }
    }

    fun scanDirectoryFromUri(uri: Uri, context: Context): List<GameItem> {
        return scanDirectoryFromUri(uri, context, emptyMap())
    }

    fun scanDirectoryFromUri(
        uri: Uri,
        context: Context,
        cachedGamesByPath: Map<String, GameItem>,
        shouldAbort: () -> Boolean = { false },
        preferEnglishTitles: Boolean = false
    ): List<GameItem> {
        if (!DocumentsContract.isTreeUri(uri)) {
            return emptyList()
        }
        return runCatching {
            val docFile = DocumentFile.fromTreeUri(context, uri) ?: return@runCatching emptyList()
            val locations = mutableMapOf<String, ScannedDocumentLocation>()
            val scanned = scanDocumentFile(
                docFile,
                context,
                cachedGamesByPath,
                shouldAbort,
                preferEnglishTitles = preferEnglishTitles,
                depth = 0,
                budget = DocumentScanBudget(),
                directorySegments = emptyList(),
                locations = locations
            ).distinctBy { libraryIdentity(it.path) }
            groupDocumentDiscs(scanned, locations, context).sortedBy { it.title.lowercase() }
        }.onFailure { error ->
            Log.w(TAG, "Unable to scan document tree: $uri", error)
        }.getOrDefault(emptyList())
    }

    fun findCoverForGame(
        path: String,
        context: Context,
        serial: String? = null,
        title: String? = null
    ): String? {
        val platform = SegaPlatformDetector.detect(context, path).takeIf { it.isSega }?.name
        val candidate = if (path.startsWith("content://")) {
            runCatching { findDocumentCover(path, context, serial, title, platform) }
                .onFailure { error -> Log.w(TAG, "Unable to find cover for document URI: $path", error) }
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
        context: Context,
        cachedGamesByPath: Map<String, GameItem>,
        shouldAbort: () -> Boolean,
        preferEnglishTitles: Boolean = false
    ): List<GameItem> {
        val items = mutableListOf<GameItem>()
        val children = dir.listFiles().orEmpty()
        val siblingsByName = children
            .filter { it.isFile }
            .associateBy { it.name.lowercase(Locale.US) }
        val cueTracks = children.filter { it.isFile && it.extension.equals("cue", true) && it.length() <= MAX_CONTAINER_BYTES }
            .flatMap { cue -> runCatching { JGenesisContentPreparer.cueReferences(cue.readText()) }.getOrDefault(emptyList()) }
            .map { it.replace('\\', '/').removePrefix("./").lowercase(Locale.ROOT) }.toSet()
        val coverCandidates = buildLocalCoverCandidates(children)
        val coverRepository = CoverArtRepository(context)
        val customCoverRepository = CustomGameCoverRepository(context)
        val titleIndex = TitleIndexRepository(context)
        val arcadeCatalog = ArcadeCatalogRepository(context)

        children.forEach { file ->
            if (shouldAbort()) return items
            when {
                file.isDirectory && normalizeBaseName(file.name) !in COVER_DIRECTORY_NAMES -> {
                    items.addAll(
                        scanLocalDirectory(
                            file,
                            context,
                            cachedGamesByPath,
                            shouldAbort,
                            preferEnglishTitles
                        )
                    )
                }

                file.isFile && (com.sbro.emucoreh.core.GameFormats.isSupportedName(file.name) || file.extension.equals("bin", true)) -> {
                    val platform = SegaPlatformDetector.detectFile(file)
                    if (file.extension.equals("bin", true) && !isStandaloneSegaBin(file.name, platform, file.name.lowercase(Locale.ROOT) in cueTracks)) return@forEach
                    if (!isLibraryContent(file.name, arcadeCatalog, platform)) return@forEach
                    val entrySize = file.length()
                    val cachedGame = cachedGamesByPath[file.absolutePath]
                    val canReuseCachedMetadata = !platform.isSega && cachedGame != null &&
                        cachedGame.fileSize == entrySize &&
                        cachedGame.lastModified == file.lastModified() &&
                        cachedGame.fileName == file.name &&
                        !cachedGame.serial.isNullOrBlank()
                    val metadata = if (canReuseCachedMetadata) {
                        com.sbro.emucoreh.core.GameMetadata(cachedGame.title, cachedGame.serial)
                    } else {
                        val sourcePath = file.absolutePath
                        val sourceMetadata = EmulatorBridge.getGameMetadata(
                            sourcePath,
                            readDiscMetadata = false
                        )
                        val discMetadata = if (platform.isSega) null else GameMetadataReader.read(context, sourcePath)
                        sourceMetadata.copy(
                            title = discMetadata?.title ?: sourceMetadata.title,
                            serial = discMetadata?.serial ?: sourceMetadata.serial
                        )
                    }
                    if (BiosValidator.isLikelyBiosLibraryEntry(file.name, metadata.title, metadata.serial)) {
                        return@forEach
                    }

                    val cleanTitle = EmulatorBridge.cleanGameDisplayTitle(metadata.title, file.name)
                    val serial = metadata.serial
                    val title = if (preferEnglishTitles && !platform.isSega) {
                        titleIndex.titleForSerial(serial) ?: cleanTitle
                    } else {
                        cleanTitle
                    }
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
                        lastModified = file.lastModified(),
                        coverArtPath = customCoverRepository.findCustomCoverPath(file.absolutePath)
                            ?: coverRepository.findCachedCoverPath(serial, title = title, platform = platform.takeIf { it.isSega }?.name)
                            ?: cachedGame?.coverArtPath?.takeIf { File(it).exists() }
                            ?: coverCandidates[normalizeBaseName(file.nameWithoutExtension)]?.absolutePath
                            ?: coverCandidates[normalizeBaseName(cleanGameName(title))]?.absolutePath,
                        serial = serial,
                        platform = platform.takeIf { it.isSega }?.name
                    )
                }
            }
        }

        return items
    }

    private fun scanDocumentFile(
        docFile: DocumentFile,
        context: Context,
        cachedGamesByPath: Map<String, GameItem>,
        shouldAbort: () -> Boolean,
        preferEnglishTitles: Boolean = false,
        depth: Int,
        budget: DocumentScanBudget,
        directorySegments: List<String>,
        locations: MutableMap<String, ScannedDocumentLocation>
    ): List<GameItem> {
        if (depth > MAX_DOCUMENT_SCAN_DEPTH || !budget.tryEnterDirectory(docFile.uri.toString())) {
            return emptyList()
        }
        val items = mutableListOf<GameItem>()
        val children = runCatching { docFile.listFiles() }.getOrDefault(emptyArray())
        val siblingsByName = lazy {
            children.mapNotNull { child ->
                documentDisplayName(context, child)?.lowercase(Locale.US)?.let { it to child }
            }.toMap()
        }
        val cueTracks = lazy {
            children.filter { child -> documentDisplayName(context, child).endsWith(".cue", true) }
                .flatMap { cue -> runCatching {
                    context.contentResolver.openInputStream(cue.uri)?.use { stream ->
                        stream.readPlaylistText()?.let(JGenesisContentPreparer::cueReferences)
                    }.orEmpty()
                }.getOrDefault(emptyList()) }
                .map { it.replace('\\', '/').removePrefix("./").lowercase(Locale.ROOT) }.toSet()
        }
        val coverCandidates = buildDocumentCoverCandidates(children)
        val coverRepository = CoverArtRepository(context)
        val customCoverRepository = CustomGameCoverRepository(context)
        val titleIndex = TitleIndexRepository(context)
        val arcadeCatalog = ArcadeCatalogRepository(context)

        for (file in children) {
            if (shouldAbort() || !budget.tryVisitEntry()) return items
            val mimeType = runCatching { file.type }.getOrNull()
            val name = documentDisplayName(context, file)
            val kind = if (name.endsWith(".bin", true)) SetupValidator.DocumentEntryKind.GAME_FILE else
                SetupValidator.classifyDocumentEntry(mimeType, name)
            when (kind) {
                SetupValidator.DocumentEntryKind.DIRECTORY,
                SetupValidator.DocumentEntryKind.UNKNOWN -> {
                    if (normalizeBaseName(name) !in COVER_DIRECTORY_NAMES) {
                        items.addAll(
                            scanDocumentFile(
                                file,
                                context,
                                cachedGamesByPath,
                                shouldAbort,
                                preferEnglishTitles,
                                depth + 1,
                                budget,
                                directorySegments + name,
                                locations
                            )
                        )
                    }
                }
                SetupValidator.DocumentEntryKind.GAME_FILE -> {
                    val uriPath = file.uri.toString()
                    val entrySize = runCatching { file.length() }.getOrDefault(0L)
                    val lastModified = runCatching { file.lastModified() }.getOrDefault(0L)
                    val cachedGame = cachedGamesByPath[uriPath]
                    // A matching cache entry already contains the expensive
                    // platform/header/disc metadata. Reusing it avoids opening
                    // every ROM and multi-gigabyte disc on each scan.
                    val canReuseCachedGame = cachedGame != null &&
                        cachedGame.fileSize == entrySize &&
                        cachedGame.lastModified == lastModified &&
                        cachedGame.fileName == name
                    if (canReuseCachedGame) {
                        locations[uriPath] = ScannedDocumentLocation(
                            document = file,
                            parentIdentity = libraryIdentity(docFile.uri.toString()),
                            segments = directorySegments + name
                        )
                        items += cachedGame
                        continue
                    }
                    // Disc extensions are shared with Dreamcast. Probe the bounded
                    // header/descriptor before caching, otherwise Sega CD disappears
                    // from platform filters until the user happens to launch a game.
                    val platform = SegaPlatformDetector.detect(context, uriPath)
                    if (name.endsWith(".bin", true) && !isStandaloneSegaBin(name, platform, name.lowercase(Locale.ROOT) in cueTracks.value)) continue
                    if (!isLibraryContent(name, arcadeCatalog, platform)) continue
                    val sourceMetadata = EmulatorBridge.getGameMetadata(
                        uriPath,
                        readDiscMetadata = false,
                        knownPlatform = platform
                    )
                    // Opening every SAF disc just to read IP.BIN dominated the
                    // first onboarding scan. The selected game resolves its
                    // disc identity on demand, so bulk scans use filename
                    // metadata for content URIs.
                    val discMetadata = if (platform.isSega || uriPath.startsWith("content://")) {
                        null
                    } else {
                        GameMetadataReader.read(context, uriPath)
                    }
                    val metadata = sourceMetadata.copy(
                        title = discMetadata?.title ?: sourceMetadata.title,
                        serial = discMetadata?.serial ?: sourceMetadata.serial
                    )

                    if (BiosValidator.isLikelyBiosLibraryEntry(name, metadata.title, metadata.serial)) {
                        continue
                    }

                    val cleanTitle = cleanScannedTitle(metadata.title, name)
                    val serial = metadata.serial
                    val title = if (preferEnglishTitles && !platform.isSega) {
                        titleIndex.titleForSerial(serial) ?: cleanTitle
                    } else {
                        cleanTitle
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
                                siblingsByName.value[siblingName.lowercase(Locale.US)]
                                    ?.let { runCatching { it.length() }.getOrNull() }
                                    ?.takeIf { it > 0L }
                            },
                            readText = { siblingName ->
                                siblingsByName.value[siblingName.lowercase(Locale.US)]
                                    ?.takeIf { runCatching { it.length() }.getOrDefault(0L) <= MAX_CONTAINER_BYTES }
                                    ?.let { sibling ->
                                        runCatching {
                                            context.contentResolver.openInputStream(sibling.uri)?.use { stream ->
                                                stream.bufferedReader().readText()
                                            }
                                        }.getOrNull()
                                    }
                            }
                        ),
                        lastModified = lastModified,
                        coverArtPath = customCoverRepository.findCustomCoverPath(uriPath)
                            ?: coverRepository.findCachedCoverUri(serial, title = title, platform = platform.takeIf { it.isSega }?.name)
                            ?: cachedGame?.coverArtPath
                            ?: coverCandidates[normalizeBaseName(name.substringBeforeLast('.'))]?.uri?.toString()
                            ?: coverCandidates[normalizeBaseName(cleanGameName(title))]?.uri?.toString(),
                        serial = serial,
                        platform = platform.takeIf { it.isSega }?.name
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
        if (platform.isSega) return true
        val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (extension) {
            in com.sbro.emucoreh.core.GameFormats.archives -> {
                val known = arcadeCatalog.isKnownRomset(name)
                if (!known) {
                    // Diagnostic for arcade reports: logcat shows which
                    // archives the Flycast game table does not recognise.
                    Log.d(TAG, "Skipping unrecognised archive: $name")
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
        children: Array<DocumentFile>
    ): Map<String, DocumentFile> {
        val directFiles = children
            .filter {
                runCatching { it.type }.getOrNull() != DocumentsContract.Document.MIME_TYPE_DIR &&
                    documentDisplayName(null, it)
                        .substringAfterLast('.', "")
                        .lowercase() in COVER_EXTENSIONS
            }
        val nestedCoverFiles = children
            .filter {
                val type = runCatching { it.type }.getOrNull()
                (type == DocumentsContract.Document.MIME_TYPE_DIR || type == null) &&
                    normalizeBaseName(documentDisplayName(null, it)) in COVER_DIRECTORY_NAMES
            }
            .flatMap { runCatching { it.listFiles().asIterable() }.getOrDefault(emptyList()) }
            .filter {
                runCatching { it.type }.getOrNull() != DocumentsContract.Document.MIME_TYPE_DIR &&
                    documentDisplayName(null, it)
                        .substringAfterLast('.', "")
                        .lowercase() in COVER_EXTENSIONS
            }

        return (directFiles + nestedCoverFiles).associateBy {
            normalizeBaseName(cleanGameName(documentDisplayName(null, it).substringBeforeLast('.')))
        }
    }

    private fun documentDisplayName(context: Context?, document: DocumentFile): String {
        val rawName = runCatching { document.name }.getOrNull().orEmpty()
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
