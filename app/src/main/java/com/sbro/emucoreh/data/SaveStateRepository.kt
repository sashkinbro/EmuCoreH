package com.sbro.emucoreh.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.sbro.emucoreh.core.DocumentPathResolver
import com.sbro.emucoreh.core.EmulatorBridge
import com.sbro.emucoreh.core.EmulatorStorage
import com.sbro.emucoreh.core.NativeApp
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.core.SegaPlatformDetector
import com.sbro.emucoreh.core.Model3Romsets
import com.sbro.emucoreh.core.Model3ContentPreparer
import com.sbro.emucoreh.core.Model3SaveStatePayload
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val AUTO_SAVE_SLOT = 0
private val SAVE_STATE_SLOTS = 0..10
private val SAVE_STATE_FILE_REGEX = Regex("""^(.+?)\.(\d{2})\.rstate$""", RegexOption.IGNORE_CASE)
private const val SAVE_STATE_MAGIC = 0x54534345
private const val CURRENT_SAVE_STATE_VERSION = 1
private val SEGA_SAVE_STATE_IDENTITY_REGEX = Regex("^JG-(GENESIS|SEGA_CD|SEGA_32X|MASTER_SYSTEM|GAME_GEAR|SG_1000)-[A-F0-9]{16}$")
private val MODEL3_SAVE_STATE_IDENTITY_REGEX = Regex("^M3-(?:[A-F0-9]{16}|[A-Z0-9_]+)$")
private const val MAX_IMPORT_FILE_BYTES = 1_073_741_824L
private const val MAX_IMPORT_ARCHIVE_BYTES = 4_294_967_296L
private const val MAX_IMPORT_CANDIDATES = 500

enum class SaveStateImportFormat {
    CURRENT,
    UNKNOWN
}

data class SaveStateImportCandidate(
    val sourceEntryName: String?,
    val originalFileName: String,
    val targetFileName: String,
    val targetSlot: Int,
    val serial: String?,
    val format: SaveStateImportFormat,
    val sizeBytes: Long,
    val version: Int = CURRENT_SAVE_STATE_VERSION,
    val coreId: Int = 0,
    val consoleTag: Int? = null,
    val model3Set: String? = null
)

data class SaveStateImportPreview(
    val stagedFilePath: String,
    val displayName: String,
    val candidates: List<SaveStateImportCandidate>,
    val skippedCount: Int,
    val incompatibleCount: Int
) {
    val importableCount: Int get() = candidates.size
}

data class SaveStateImportResult(
    val importedCount: Int,
    val failedCount: Int
) {
    val isSuccess: Boolean get() = importedCount > 0 && failedCount == 0
}

internal data class ParsedExternalSaveStateName(
    val serial: String,
    val crc: String?,
    val slot: Int
)

internal fun parseExternalSaveStateName(fileName: String): ParsedExternalSaveStateName? {
    val cleanName = fileName.substringAfterLast('/').substringAfterLast('\\')
    val match = SAVE_STATE_FILE_REGEX.matchEntire(cleanName) ?: return null
    return ParsedExternalSaveStateName(
        serial = match.groupValues[1].normalizeSaveStateSerialKey() ?: return null,
        crc = null,
        slot = match.groupValues[2].toIntOrNull()?.takeIf { it in SAVE_STATE_SLOTS } ?: return null
    )
}

internal fun saveStateFormatForVersion(version: Int?): SaveStateImportFormat {
    return if (version == CURRENT_SAVE_STATE_VERSION || version == 2) SaveStateImportFormat.CURRENT
    else SaveStateImportFormat.UNKNOWN
}

internal data class SaveStateImportHeader(val version: Int, val coreId: Int, val consoleTag: Int? = null, val model3Set: String? = null) {
    val platform: EmulationPlatform? get() = when (coreId) {
        0 -> EmulationPlatform.DREAMCAST
        2 -> EmulationPlatform.MODEL_3
        1 -> when (consoleTag) {
        0 -> EmulationPlatform.GENESIS
        1, 3 -> EmulationPlatform.SEGA_CD
        2 -> EmulationPlatform.SEGA_32X
        4 -> EmulationPlatform.MASTER_SYSTEM
        5 -> EmulationPlatform.GAME_GEAR
        6 -> EmulationPlatform.SG_1000
        else -> null
        }
        else -> null
    }
}

/** Legacy cores need a prefix; Model 3 uses bounded streaming validation of its full checksum. */
internal fun readSaveStateImportHeader(input: InputStream): SaveStateImportHeader? {
    val version = readEmuCoreHSaveStateVersion(input) ?: return null
    if (version == 1) return SaveStateImportHeader(1, 0)
    if (version != 2) return null
    val identity = ByteArray(4)
    var identityOffset = 0
    while (identityOffset < identity.size) {
        val count = input.read(identity, identityOffset, identity.size - identityOffset)
        if (count <= 0) return null
        identityOffset += count
    }
    if (identity.contentEquals(byteArrayOf(2, 0, 0, 0))) {
        val metadata = Model3SaveStatePayload.read(input) ?: return null
        return SaveStateImportHeader(2, 2, model3Set = metadata.setName)
    }
    if (!identity.contentEquals(byteArrayOf(1, 0, 0, 0))) return null
    val suffix = ByteArray(10) // JGLRSTA1, console ID, at least one payload byte
    var offset = 0
    while (offset < suffix.size) {
        val count = input.read(suffix, offset, suffix.size - offset)
        if (count <= 0) return null
        offset += count
    }
    if (!suffix.copyOfRange(0, 8).contentEquals("JGLRSTA1".toByteArray(Charsets.US_ASCII))) return null
    return SaveStateImportHeader(2, 1, suffix[8].toInt() and 0xff).takeIf { it.platform != null }
}

internal fun isSaveStateImportCompatible(
    header: SaveStateImportHeader,
    fileName: String,
    selectedPlatform: EmulationPlatform?
): Boolean {
    val platform = header.platform ?: return false
    if (selectedPlatform != null && selectedPlatform != platform) return false
    val cleanName = fileName.substringAfterLast('/').substringAfterLast('\\')
    val identity = SAVE_STATE_FILE_REGEX.matchEntire(cleanName)?.groupValues?.get(1)?.uppercase(Locale.ROOT)
    val namespaced = cleanName.startsWith("JG-", ignoreCase = true)
    val model3Namespaced = cleanName.startsWith("M3-", ignoreCase = true)
    if (header.coreId == 0) return !namespaced && !model3Namespaced
    if (header.coreId == 2) {
        if (namespaced || header.model3Set == null) return false
        if (!model3Namespaced) return selectedPlatform == EmulationPlatform.MODEL_3
        if (identity == null || !MODEL3_SAVE_STATE_IDENTITY_REGEX.matches(identity)) return false
        val key = identity.removePrefix("M3-")
        return key.matches(Regex("[A-F0-9]{16}")) || key.equals(header.model3Set, true)
    }
    if (model3Namespaced) return false
    if (!namespaced) return selectedPlatform != null
    val match = identity?.let(SEGA_SAVE_STATE_IDENTITY_REGEX::matchEntire) ?: return false
    return match.groupValues[1] == platform.name
}

internal fun allocateSaveStateSlot(preferred: Int?, occupied: Set<Int>): Int? {
    if (preferred != null && preferred in SAVE_STATE_SLOTS && preferred !in occupied) return preferred
    return (1..10).firstOrNull { it !in occupied }
}

internal fun findFallbackSaveStateFile(
    files: Array<out File>,
    gameSerial: String?,
    slot: Int
): File? {
    val normalizedSerial = gameSerial.normalizeSaveStateSerialKey() ?: return null
    return files.asSequence()
        .filter(File::isFile)
        .mapNotNull { file ->
            val match = SAVE_STATE_FILE_REGEX.matchEntire(file.name) ?: return@mapNotNull null
            val serial = match.groupValues[1].normalizeSaveStateSerialKey() ?: return@mapNotNull null
            val fileSlot = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val model3Set = normalizedSerial.takeIf { it.startsWith("M3-") }?.removePrefix("M3-")
                ?.let { Model3Romsets.find("$it.zip")?.name }
            val sameSet = model3Set != null && serial.startsWith("M3-") && fileSlot == slot &&
                runCatching { file.inputStream().use(::readSaveStateImportHeader)?.model3Set == model3Set }.getOrDefault(false)
            file.takeIf { fileSlot == slot && (serial == normalizedSerial || sameSet) }
        }
        .maxByOrNull(File::lastModified)
}

private fun String?.normalizeSaveStateSerialKey(): String? {
    if (this.isNullOrBlank()) return null
    val cleanSerial = trim().uppercase(Locale.ROOT)
    // Hashes can themselves contain four letters followed by five digits; never
    // let the legacy product-code normalizer truncate a Sega identity.
    if (SEGA_SAVE_STATE_IDENTITY_REGEX.matches(cleanSerial) || MODEL3_SAVE_STATE_IDENTITY_REGEX.matches(cleanSerial)) return cleanSerial
    val regex = Regex("([A-Z]{4})[^A-Z0-9]*([0-9]{3})[^A-Z0-9]*([0-9]{2})")
    val altRegex = Regex("([A-Z]{4})[^A-Z0-9]*([0-9]{5})")
    regex.find(cleanSerial)?.let { match ->
        return "${match.groupValues[1]}-${match.groupValues[2]}${match.groupValues[3]}"
    }
    altRegex.find(cleanSerial)?.let { match ->
        return "${match.groupValues[1]}-${match.groupValues[2]}"
    }
    return cleanSerial.replace(Regex("[^A-Z0-9_-]"), "").takeIf { it.isNotBlank() }
}

data class SaveStateSlotInfo(
    val slot: Int,
    val exists: Boolean,
    val absolutePath: String?,
    val fileName: String?,
    val sizeBytes: Long,
    val lastModified: Long,
    val isAutoSave: Boolean = slot == AUTO_SAVE_SLOT
)

data class SaveStateEntryInfo(
    val absolutePath: String,
    val fileName: String,
    val serial: String,
    val crc: String?,
    val slot: Int,
    val sizeBytes: Long,
    val lastModified: Long,
    val gamePath: String?,
    val gameTitle: String,
    val coverArtPath: String?,
    val isAutoSave: Boolean = slot == AUTO_SAVE_SLOT
) {
    val canLoad: Boolean get() = !gamePath.isNullOrBlank()
}

class SaveStateRepository(private val context: Context) {

    private var saveDirectoryOverride: File? = null

    /** Keep repository integration tests independent of the app's process-wide DataStore. */
    internal constructor(context: Context, saveDirectory: File) : this(context) {
        saveDirectoryOverride = saveDirectory
    }

    private val gameRepository = GameRepository()
    private val gameLibraryCacheRepository = GameLibraryCacheRepository(context)
    private val coverArtRepository = CoverArtRepository(context)
    private val preferences = AppPreferences(context)

    fun listSlots(gamePath: String, gameSerial: String? = null): List<SaveStateSlotInfo> {
        val saveStateFiles = saveStatesDir().listFiles().orEmpty()
        return SAVE_STATE_SLOTS.map { slot ->
            val path = runCatching { NativeApp.getSaveStatePathForFile(gamePath, slot) }.getOrNull()
            val file = resolveSaveStateFile(
                nativePath = path,
                saveStateFiles = saveStateFiles,
                gameSerial = gameSerial,
                slot = slot
            )
            SaveStateSlotInfo(
                slot = slot,
                exists = file?.exists() == true,
                absolutePath = file?.absolutePath,
                fileName = file?.name,
                sizeBytes = file?.takeIf(File::exists)?.length() ?: 0L,
                lastModified = file?.takeIf(File::exists)?.lastModified() ?: 0L
            )
        }
    }

    private fun resolveSaveStateFile(
        nativePath: String?,
        saveStateFiles: Array<out File>,
        gameSerial: String?,
        slot: Int
    ): File? {
        nativePath?.let(::File)?.takeIf(File::exists)?.let { return it }
        // GameList can still be warming up after a cold launch. The library already knows the
        // serial, so use it to locate the slot without waiting for native metadata discovery.
        return findFallbackSaveStateFile(saveStateFiles, gameSerial, slot)
    }

    fun findLatestSlot(gamePath: String, gameSerial: String? = null): Int? {
        return listSlots(gamePath, gameSerial)
            .filter { it.exists }
            .maxByOrNull { it.lastModified }
            ?.slot
    }

    fun listEntries(
        filterGamePath: String? = null,
        filterGameTitle: String? = null,
        filterGameSerial: String? = null
    ): List<SaveStateEntryInfo> {
        return if (filterGamePath.isNullOrBlank()) {
            listAllEntriesFast()
        } else {
            listEntriesForGame(filterGamePath, filterGameTitle, filterGameSerial)
        }
    }

    suspend fun enrichGlobalEntries(entries: List<SaveStateEntryInfo>): List<SaveStateEntryInfo> {
        if (entries.isEmpty()) return entries
        return enrichGlobalEntries(entries, loadLibraryIndex())
    }

    /** Deterministic library seam for repository/native workflow coverage without changing saved library preferences. */
    internal fun enrichGlobalEntries(entries: List<SaveStateEntryInfo>, games: List<GameItem>): List<SaveStateEntryInfo> =
        enrichGlobalEntries(entries, buildLibraryIndex(flattenLibraryGroups(games)))

    private fun enrichGlobalEntries(entries: List<SaveStateEntryInfo>, libraryIndex: LibraryIndex): List<SaveStateEntryInfo> {
        val unresolvedSlots = entries.asSequence()
            .filter { it.gamePath == null }
            .map { it.slot }
            .toSet()
        val savePathToGame = HashMap<String, GameItem>()
        if (unresolvedSlots.isNotEmpty()) {
            libraryIndex.allGames.forEach { game ->
                if (game.path.isBlank()) return@forEach
                unresolvedSlots.forEach { slot ->
                    val expected = runCatching {
                        NativeApp.getSaveStatePathForFile(game.path, slot)
                    }.getOrNull()
                    if (!expected.isNullOrBlank()) {
                        savePathToGame[expected.lowercase(Locale.ROOT)] = game
                    }
                }
            }
        }
        return entries.map { entry ->
            if (entry.gamePath != null && !entry.coverArtPath.isNullOrBlank()) {
                entry
            } else {
                val match = libraryIndex.findBestMatch(serial = entry.serial, titleHint = entry.gameTitle)
                    ?: savePathToGame[entry.absolutePath.lowercase(Locale.ROOT)]
                if (match == null) {
                    entry
                } else {
                    entry.copy(
                        gamePath = match.path,
                        gameTitle = match.title,
                        coverArtPath = match.coverArtPath ?: entry.coverArtPath
                    )
                }
            }
        }
    }

    fun deleteEntry(entry: SaveStateEntryInfo): Boolean {
        val deleted = runCatching {
            File(entry.absolutePath).takeIf(File::exists)?.delete() == true
        }.getOrDefault(false)
        if (deleted) {
            runCatching { File("${entry.absolutePath}.png").delete() }
        }
        return deleted
    }

    fun backupEntries(entries: List<SaveStateEntryInfo>, destination: Uri): Boolean {
        val contentResolver = context.contentResolver
        val existingEntries = entries.map { File(it.absolutePath) }.filter(File::exists)
        if (existingEntries.isEmpty()) return false
        return runCatching {
            contentResolver.openOutputStream(destination)?.use { output ->
                ZipOutputStream(output).use { zip ->
                    val manifest = JSONObject().put(
                        "entries",
                        JSONArray().apply {
                            existingEntries.forEach { file ->
                                put(JSONObject().put("fileName", file.name))
                            }
                        }
                    )
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(manifest.toString().toByteArray())
                    zip.closeEntry()
                    existingEntries.forEach { file ->
                        zip.putNextEntry(ZipEntry("slots/${file.name}"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        val sidecar = File("${file.absolutePath}.png")
                        if (isValidSaveStatePreview(sidecar)) {
                            zip.putNextEntry(ZipEntry("slots/${file.name}.png"))
                            sidecar.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            } != null
        }.getOrDefault(false)
    }

    fun analyzeImport(
        source: Uri,
        gamePath: String? = null,
        gameSerial: String? = null
    ): SaveStateImportPreview {
        val displayName = readDisplayName(source)
        val importDir = importCacheDir()
        val stagedFile = File(importDir, "save-import-${UUID.randomUUID()}.bin")
        try {
            context.contentResolver.openInputStream(source)?.use { input ->
                stagedFile.outputStream().use { output ->
                    input.copyBoundedTo(output, MAX_IMPORT_ARCHIVE_BYTES)
                }
            } ?: error("Unable to open the selected file")

            val directVersion = stagedFile.inputStream().use(::readEmuCoreHSaveStateVersion)
            val rawCandidates = if (directVersion != null) {
                listOf(
                    RawImportCandidate(
                        entryName = null,
                        fileName = displayName,
                        sizeBytes = stagedFile.length(),
                        header = stagedFile.inputStream().use(::readSaveStateImportHeader)
                    )
                )
            } else {
                ZipFile(stagedFile).use { zip ->
                    collectArchiveCandidates(zip)
                }
            }

            return buildImportPreview(
                stagedFile = stagedFile,
                displayName = displayName,
                rawCandidates = rawCandidates,
                gamePath = gamePath,
                gameSerial = gameSerial
            )
        } catch (error: Throwable) {
            stagedFile.delete()
            throw error
        }
    }

    fun importStates(preview: SaveStateImportPreview): SaveStateImportResult {
        val stagedFile = File(preview.stagedFilePath)
        val importDir = importCacheDir().canonicalFile
        require(stagedFile.canonicalFile.parentFile == importDir) { "Invalid staged import path" }
        if (!stagedFile.isFile) return SaveStateImportResult(importedCount = 0, failedCount = preview.candidates.size)

        var imported = 0
        var failed = 0
        try {
            val importCandidate: (SaveStateImportCandidate, ZipFile?) -> Unit = { candidate, zip ->
                    val target = File(saveStatesDir(), candidate.targetFileName)
                    if (target.exists()) {
                        failed++
                    } else {
                        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.importing")
                        val success = runCatching {
                            target.parentFile?.mkdirs()
                            val input = candidate.sourceEntryName?.let { entryName ->
                                requireNotNull(zip) { "Archive was not opened" }
                                zip.getEntry(entryName)?.let(zip::getInputStream)
                            } ?: stagedFile.inputStream()
                            requireNotNull(input) { "Save-state entry disappeared" }.use { source ->
                                temporary.outputStream().use { output ->
                                    source.copyBoundedTo(output, MAX_IMPORT_FILE_BYTES)
                                }
                            }
                            val header = temporary.inputStream().use(::readSaveStateImportHeader)
                            check(header != null && header == SaveStateImportHeader(candidate.version, candidate.coreId, candidate.consoleTag, candidate.model3Set) &&
                                isSaveStateImportCompatible(header, candidate.targetFileName, null)) {
                                "Invalid EmuCoreH save state"
                            }
                            check(!target.exists()) { "Target slot is no longer empty" }
                            check(temporary.renameTo(target)) { "Unable to finalize imported save state" }
                        }.isSuccess
                        if (success) {
                            imported++
                            runCatching { candidate.sourceEntryName?.let { stateEntry ->
                                zip?.getEntry("$stateEntry.png")?.takeUnless(ZipEntry::isDirectory)?.let { preview ->
                                    if (preview.size in 1..MAX_SAVE_PREVIEW_BYTES) {
                                        zip.getInputStream(preview).use { input ->
                                            copySaveStatePreview(input, File("${target.absolutePath}.png"))
                                        }
                                    }
                                }
                            } }
                        } else {
                            temporary.delete()
                            failed++
                        }
                    }
            }
            if (preview.candidates.all { it.sourceEntryName == null }) {
                preview.candidates.forEach { importCandidate(it, null) }
            } else {
                ZipFile(stagedFile).use { zip -> preview.candidates.forEach { importCandidate(it, zip) } }
            }
        } finally {
            stagedFile.delete()
        }
        return SaveStateImportResult(importedCount = imported, failedCount = failed)
    }

    fun discardImport(preview: SaveStateImportPreview?) {
        val path = preview?.stagedFilePath ?: return
        runCatching {
            val file = File(path).canonicalFile
            if (file.parentFile == importCacheDir().canonicalFile) file.delete()
        }
    }

    private fun buildImportPreview(
        stagedFile: File,
        displayName: String,
        rawCandidates: List<RawImportCandidate>,
        gamePath: String?,
        gameSerial: String?
    ): SaveStateImportPreview {
        val occupiedNames = saveStatesDir().listFiles().orEmpty().filter(File::isFile).mapTo(mutableSetOf()) { it.name }
        val occupiedSlots = if (gamePath.isNullOrBlank()) {
            mutableMapOf<String, MutableSet<Int>>()
        } else {
            mutableMapOf("selected" to listSlots(gamePath, gameSerial).filter { it.exists }.mapTo(mutableSetOf()) { it.slot })
        }
        val normalizedGameSerial = gameSerial.normalizeSaveStateSerialKey()
        val selectedPlatform = gamePath?.takeIf { it.isNotBlank() }?.let { SegaPlatformDetector.detect(context, it) }
        // Explicit import may inspect the selected ZIP once. Never repeat a SAF
        // materialization for each slot or rely on an old archive's display name.
        val selectedModel3Set = if (selectedPlatform?.isModel3 == true && !gamePath.isNullOrBlank()) {
            Model3ContentPreparer.resolveExpectedSet(context, gamePath)
        } else null
        var skipped = 0
        var incompatible = 0
        val candidates = mutableListOf<SaveStateImportCandidate>()

        rawCandidates.take(MAX_IMPORT_CANDIDATES).forEach { raw ->
            val header = raw.header
            val format = saveStateFormatForVersion(header?.version)
            if (header == null || format == SaveStateImportFormat.UNKNOWN || raw.sizeBytes !in 1..MAX_IMPORT_FILE_BYTES ||
                !isSaveStateImportCompatible(header, raw.fileName, selectedPlatform)) {
                incompatible++
                return@forEach
            }
            val parsed = parseExternalSaveStateName(raw.fileName)
            if (header.coreId == 0 && normalizedGameSerial != null && parsed?.serial != null && parsed.serial != normalizedGameSerial) {
                skipped++
                return@forEach
            }
            if (header.coreId == 2 && !gamePath.isNullOrBlank()) {
                if (selectedModel3Set == null || selectedModel3Set != header.model3Set) {
                    skipped++
                    return@forEach
                }
            }

            val slotKey = if (gamePath.isNullOrBlank()) {
                parsed?.serial
            } else {
                "selected"
            }
            if (slotKey == null) {
                skipped++
                return@forEach
            }
            val slots = occupiedSlots.getOrPut(slotKey) {
                occupiedNames.mapNotNullTo(mutableSetOf()) { existing ->
                    parseExternalSaveStateName(existing)?.takeIf { it.serial == slotKey }?.slot
                }
            }
            val targetSlot = allocateSaveStateSlot(parsed?.slot, slots)
            if (targetSlot == null) {
                skipped++
                return@forEach
            }
            val targetName = if (!gamePath.isNullOrBlank()) {
                runCatching { NativeApp.getSaveStatePathForFile(gamePath, targetSlot) }
                    .getOrNull()
                    ?.let(::File)
                    ?.name
                    ?.takeIf { it.endsWith(".rstate", ignoreCase = true) }
                    ?: parsed?.let { "${it.serial}.${targetSlot.toString().padStart(2, '0')}.rstate" }
            } else {
                parsed?.let { "${it.serial}.${targetSlot.toString().padStart(2, '0')}.rstate" }
            }
            if (targetName.isNullOrBlank() || targetName in occupiedNames ||
                !isSaveStateImportCompatible(header, targetName, null)) {
                skipped++
                return@forEach
            }
            occupiedNames += targetName
            slots += targetSlot
            candidates += SaveStateImportCandidate(
                sourceEntryName = raw.entryName,
                originalFileName = raw.fileName,
                targetFileName = targetName,
                targetSlot = targetSlot,
                serial = parsed?.serial ?: normalizedGameSerial,
                format = format,
                sizeBytes = raw.sizeBytes,
                version = header.version,
                coreId = header.coreId,
                consoleTag = header.consoleTag,
                model3Set = header.model3Set
            )
        }
        skipped += (rawCandidates.size - MAX_IMPORT_CANDIDATES).coerceAtLeast(0)

        return SaveStateImportPreview(
            stagedFilePath = stagedFile.absolutePath,
            displayName = displayName,
            candidates = candidates,
            skippedCount = skipped,
            incompatibleCount = incompatible
        )
    }

    private fun collectArchiveCandidates(zip: ZipFile): List<RawImportCandidate> {
        return zip.entries().asSequence()
            .filterNot(ZipEntry::isDirectory)
            .filter { entry ->
                val normalized = entry.name.replace('\\', '/').trimStart('/')
                normalized.startsWith("slots/", ignoreCase = true) &&
                    normalized.endsWith(".rstate", ignoreCase = true)
            }
            .take(MAX_IMPORT_CANDIDATES + 1)
            .map { entry ->
                val header = zip.getInputStream(entry).use(::readSaveStateImportHeader)
                RawImportCandidate(
                    entryName = entry.name,
                    fileName = entry.name.substringAfterLast('/').substringAfterLast('\\'),
                    sizeBytes = entry.size,
                    header = header
                )
            }
            .toList()
    }

    private fun readDisplayName(uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "save-state.rstate"
    }

    private fun importCacheDir(): File = File(context.cacheDir, "save-state-imports").apply { mkdirs() }

    private data class RawImportCandidate(
        val entryName: String?,
        val fileName: String,
        val sizeBytes: Long,
        val header: SaveStateImportHeader?
    )

    fun getPreviewImagePath(entry: SaveStateEntryInfo): String? =
        getPreviewImagePath(File(entry.absolutePath))

    fun getPreviewImagePath(saveFile: File): String? {
        if (!saveFile.exists()) return null

        val sidecar = File("${saveFile.absolutePath}.png")
        if (sidecar.isFile && sidecar.length() > 0L) return sidecar.absolutePath

        val previewDir = File(context.cacheDir, "save-previews").apply { mkdirs() }
        val cacheKey = saveFile.absolutePath.sha1()
        val previewFile = File(previewDir, "$cacheKey-${saveFile.lastModified()}.png")
        if (previewFile.isFile && previewFile.length() > 0L) {
            return previewFile.absolutePath
        }

        return runCatching {
            val bytes = NativeApp.getSaveStateScreenshot(saveFile.absolutePath) ?: return@runCatching null
            val temporary = File.createTempFile(".preview-", ".tmp", previewDir)
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                }
                if (!temporary.renameTo(previewFile)) {
                    temporary.copyTo(previewFile, overwrite = true)
                }
            } finally {
                temporary.delete()
            }
            previewFile.setLastModified(saveFile.lastModified())
            // Keep a single cached preview per save-state file: older revisions become
            // unreachable as soon as the state is overwritten.
            previewDir.listFiles { file ->
                file.isFile && file.name.startsWith("$cacheKey-") && file != previewFile
            }?.forEach(File::delete)
            previewDir.listFiles { file ->
                file.isFile && file.name == "$cacheKey.png"
            }?.forEach(File::delete)
            previewFile.absolutePath
        }.getOrNull()
    }

    private fun listAllEntriesFast(): List<SaveStateEntryInfo> {
        val files = saveStatesDir()
            .listFiles()
            .orEmpty()
            .filter { it.isFile && isPrimarySaveState(it.name) }

        return files.mapNotNull { file ->
            val parsed = parseSaveStateName(file.name) ?: return@mapNotNull null
            parsed.toFastEntry(file)
        }.sortedWith(
            compareByDescending<SaveStateEntryInfo> { it.lastModified }
                .thenBy { it.gameTitle.lowercase(Locale.ROOT) }
                .thenBy { it.slot }
        )
    }

    private fun saveStatesDir(): File {
        saveDirectoryOverride?.let { return it.apply { mkdirs() } }
        return EmulatorStorage.saveStatesDir(context, preferences.getEmulatorDataPathSync())
    }

    private fun listEntriesForGame(
        gamePath: String,
        gameTitle: String?,
        gameSerial: String?
    ): List<SaveStateEntryInfo> {
        val fallbackInfo = resolveFallbackGameInfo(gamePath, gameTitle, gameSerial)
        val targetSerial = fallbackInfo.serial

        return listSlots(gamePath, targetSerial)
            .filter { it.exists }
            .mapNotNull { slot ->
                val file = slot.absolutePath?.let(::File)?.takeIf(File::exists) ?: return@mapNotNull null
                val absolutePath = file.absolutePath

                val parsed = parseSaveStateName(file.name)
                val serialKey = parsed?.serial ?: targetSerial ?: return@mapNotNull null
                SaveStateEntryInfo(
                    absolutePath = absolutePath,
                    fileName = file.name,
                    serial = serialKey,
                    crc = parsed?.crc,
                    slot = slot.slot,
                    sizeBytes = slot.sizeBytes,
                    lastModified = slot.lastModified,
                    gamePath = gamePath,
                    gameTitle = fallbackInfo.title,
                    coverArtPath = fallbackInfo.coverArtPath
                        ?: coverArtRepository.findCachedCoverPath(serialKey),
                    isAutoSave = slot.isAutoSave
                )
            }
            .sortedByDescending { it.lastModified }
    }

    private suspend fun loadLibraryIndex(): LibraryIndex {
        val libraryPaths = preferences.gamePaths.first()
        val cachedLibraryGames = when {
            libraryPaths.isEmpty() -> emptyList()
            else -> flattenLibraryGroups(
                gameLibraryCacheRepository.loadSnapshot(GameLibraryCacheRepository.libraryKey(libraryPaths)).games
            )
        }
        val recentGames = preferences.recentGames.first().map { recent ->
            GameItem(
                title = recent.title.ifBlank { recent.path.substringAfterLast('/').substringBeforeLast('.') },
                path = recent.path,
                fileName = recent.path.substringAfterLast('/'),
                fileSize = 0L,
                lastModified = recent.lastPlayedAt,
                coverArtPath = null,
                serial = recent.serial
            )
        }
        val libraryGames = (cachedLibraryGames + recentGames)
            .distinctBy { it.path }

        return buildLibraryIndex(libraryGames)
    }

    private fun buildLibraryIndex(libraryGames: List<GameItem>): LibraryIndex {
        val bySerial = linkedMapOf<String, GameItem>()
        val byTitle = linkedMapOf<String, MutableList<GameItem>>()
        val byPath = linkedMapOf<String, GameItem>()

        libraryGames.forEach { game ->
            byPath[game.path] = game
            game.serial.normalizeSerialKey()?.let { serial ->
                bySerial.putIfAbsent(serial, game)
            }
            normalizeTitleKey(game.title)?.let { key ->
                byTitle.getOrPut(key) { mutableListOf() }.add(game)
            }
        }

        return LibraryIndex(
            bySerial = bySerial,
            byTitle = byTitle,
            byPath = byPath,
            allGames = libraryGames
        )
    }

    private fun resolveFallbackGameInfo(
        gamePath: String,
        gameTitle: String?,
        gameSerial: String? = null
    ): FallbackGameInfo {
        val resolvedPath = DocumentPathResolver.resolveFilePath(context, gamePath) ?: gamePath
        val metadata = runCatching { EmulatorBridge.getGameMetadata(resolvedPath) }.getOrNull()
        val normalizedSerial = gameSerial.normalizeSerialKey() ?: metadata?.serial.normalizeSerialKey()
        val cleanPassedTitle = gameTitle.takeIf(::isUsableTitle)
        val cleanMetadataTitle = metadata?.title?.takeIf(::isUsableTitle)
        val title = when {
            cleanPassedTitle != null -> cleanPassedTitle
            cleanMetadataTitle != null -> cleanMetadataTitle
            else -> DocumentPathResolver.getDisplayName(context, gamePath).substringBeforeLast('.')
        }.ifBlank { DocumentPathResolver.getDisplayName(context, gamePath).substringBeforeLast('.') }
        val coverArtPath = gameRepository.findCoverForGame(
            path = gamePath,
            context = context,
            serial = normalizedSerial,
            title = title
        ) ?: coverArtRepository.findCachedCoverPath(normalizedSerial)
        return FallbackGameInfo(
            serial = normalizedSerial,
            title = title,
            coverArtPath = coverArtPath
        )
    }

    private fun ParsedSaveStateName.toFastEntry(file: File): SaveStateEntryInfo {
        val resolvedTitle = serial
        return SaveStateEntryInfo(
            absolutePath = file.absolutePath,
            fileName = file.name,
            serial = serial,
            crc = crc,
            slot = slot,
            sizeBytes = file.length(),
            lastModified = file.lastModified(),
            gamePath = null,
            gameTitle = resolvedTitle,
            coverArtPath = coverArtRepository.findCachedCoverPath(serial),
            isAutoSave = slot == AUTO_SAVE_SLOT
        )
    }

    private fun parseSaveStateName(fileName: String): ParsedSaveStateName? {
        val match = SAVE_STATE_FILE_REGEX.matchEntire(fileName) ?: return null
        return ParsedSaveStateName(
            serial = match.groupValues[1].normalizeSerialKey() ?: return null,
            crc = null,
            slot = match.groupValues[2].toIntOrNull() ?: return null
        )
    }

    private fun isPrimarySaveState(fileName: String): Boolean {
        return fileName.endsWith(".rstate", ignoreCase = true) &&
            !fileName.endsWith(".backup", ignoreCase = true) &&
            !fileName.contains(".resume.", ignoreCase = true)
    }

    private fun String?.normalizeSerialKey(): String? {
        return normalizeSaveStateSerialKey()
    }

    private fun normalizeTitleKey(value: String?): String? {
        return value
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun isUsableTitle(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val trimmed = value.trim()
        return !trimmed.startsWith("content://") &&
            !trimmed.startsWith("primary%3A", ignoreCase = true) &&
            !trimmed.contains("%2F", ignoreCase = true) &&
            !trimmed.contains("%3A", ignoreCase = true)
    }

    private data class ParsedSaveStateName(
        val serial: String,
        val crc: String?,
        val slot: Int
    )

    private data class FallbackGameInfo(
        val serial: String?,
        val title: String,
        val coverArtPath: String?
    )

    private data class LibraryIndex(
        val bySerial: Map<String, GameItem>,
        val byTitle: Map<String, List<GameItem>>,
        val byPath: Map<String, GameItem>,
        val allGames: List<GameItem>
    ) {
        fun findBestMatch(serial: String?, titleHint: String?): GameItem? {
            serial?.let { bySerial[it] }?.let { return it }
            val titleKey = titleHint
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
            return titleKey?.let { byTitle[it]?.firstOrNull() }
        }
    }

}

private fun String.sha1(): String {
    val digest = MessageDigest.getInstance("SHA-1")
    return digest.digest(toByteArray()).joinToString("") { "%02x".format(it) }
}

private fun readEmuCoreHSaveStateVersion(input: InputStream): Int? {
    val bytes = ByteArray(8)
    var offset = 0
    while (offset < bytes.size) {
        val read = input.read(bytes, offset, bytes.size - offset)
        if (read <= 0) return null
        offset += read
    }
    val magic = (bytes[0].toInt() and 0xff) or
        ((bytes[1].toInt() and 0xff) shl 8) or
        ((bytes[2].toInt() and 0xff) shl 16) or
        ((bytes[3].toInt() and 0xff) shl 24)
    if (magic != SAVE_STATE_MAGIC) return null
    return (bytes[4].toInt() and 0xff) or
        ((bytes[5].toInt() and 0xff) shl 8) or
        ((bytes[6].toInt() and 0xff) shl 16) or
        ((bytes[7].toInt() and 0xff) shl 24)
}

private fun InputStream.copyBoundedTo(output: java.io.OutputStream, maxBytes: Long): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        require(total <= maxBytes) { "Selected save-state file is too large" }
        output.write(buffer, 0, read)
    }
    return total
}
