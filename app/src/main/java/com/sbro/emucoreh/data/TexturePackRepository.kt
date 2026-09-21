package com.sbro.emucoreh.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.sbro.emucoreh.core.EmulatorStorage
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first

data class TexturePackInfo(
    val serial: String,
    val gameTitle: String?,
    val replacementCount: Int,
    val dumpCount: Int,
    val sizeBytes: Long,
    val lastModifiedAt: Long
)

data class TexturePackSummary(
    val rootPath: String,
    val packs: List<TexturePackInfo>,
    val totalReplacementCount: Int,
    val totalDumpCount: Int,
    val totalSizeBytes: Long
)

data class TextureImportResult(
    val success: Boolean,
    val importedFiles: Int = 0,
    val importedSerials: Set<String> = emptySet()
)

/**
 * Installs and manages Flycast replacement texture packs.
 *
 * Packs live under `<system>/dc/textures/<GAME_ID>/`, the directory Flycast
 * itself resolves custom textures from. The game id is the disc product code
 * from the IP.BIN (for example `MK-51035`), so the emulator picks the pack up
 * without any extra configuration.
 */
class TexturePackRepository(
    private val context: Context,
    private val preferences: AppPreferences
) {
    private val textureExtensions = setOf(
        "png", "jpg", "jpeg", "tga", "bmp", "dds", "ktx2", "webp", "zim"
    )
    private val serialPattern = Regex("\\b[A-Z0-9]{1,4}[-_ ][0-9]{3,5}[A-Z]?\\b", RegexOption.IGNORE_CASE)
    private val libraryCacheRepository = GameLibraryCacheRepository(context.applicationContext)

    suspend fun listPacks(): TexturePackSummary {
        val root = texturesRoot()
        val libraryTitles = loadLibraryTitlesBySerial()
        val packs = root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.mapNotNull { folder -> buildPackInfo(folder, libraryTitles) }
            ?.sortedWith(
                compareBy<TexturePackInfo> { (it.gameTitle ?: it.serial).lowercase(Locale.US) }
                    .thenBy { it.serial }
            )
            ?.toList()
            .orEmpty()

        return TexturePackSummary(
            rootPath = root.absolutePath,
            packs = packs,
            totalReplacementCount = packs.sumOf { it.replacementCount },
            totalDumpCount = packs.sumOf { it.dumpCount },
            totalSizeBytes = packs.sumOf { it.sizeBytes }
        )
    }

    fun importPackZip(uri: Uri): TextureImportResult {
        val displayName = displayName(uri)
        val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return TextureImportResult(success = false)
        return input.use { importPackZip(it, displayName) }
    }

    fun installRemotePack(archive: File, targetSerial: String): TextureImportResult {
        if (!archive.isFile) return TextureImportResult(success = false)
        return archive.inputStream().use { input ->
            importPackZip(
                input = input,
                displayName = archive.name,
                targetSerial = targetSerial,
                replaceExisting = true
            )
        }
    }

    internal fun importPackZip(
        input: InputStream,
        displayName: String?,
        targetSerial: String? = null,
        replaceExisting: Boolean = false
    ): TextureImportResult {
        val normalizedTargetSerial = targetSerial?.let(::normalizeSerial)
        if (targetSerial != null && normalizedTargetSerial == null) return TextureImportResult(success = false)
        val stagingRoot = File(texturesRoot(), ".texture-import-${UUID.randomUUID()}")
        val fallbackSerial = findSerial(displayName)
        val sourceRoot = File(stagingRoot, ".source")
        val sourceFiles = linkedSetOf<String>()
        var entryCount = 0
        var declaredBytes = 0L
        var totalBytes = 0L

        return try {
            require(stagingRoot.mkdirs()) { "Could not create texture staging directory" }
            require(sourceRoot.mkdirs()) { "Could not create texture staging directory" }
            val canonicalStagingRoot = stagingRoot.canonicalFile
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    try {
                        entryCount++
                        require(entryCount <= MAX_ARCHIVE_ENTRIES) { "Texture archive contains too many entries" }
                        if (entry.isDirectory) continue
                        require(entry.method == ZipEntry.STORED || entry.method == ZipEntry.DEFLATED) {
                            "Unsupported texture archive compression"
                        }
                        if (entry.size >= 0) {
                            declaredBytes = Math.addExact(declaredBytes, entry.size)
                            require(declaredBytes <= MAX_ARCHIVE_BYTES) { "Texture archive is too large" }
                        }
                        val cleanParts = cleanZipPath(entry.name) ?: error("Invalid texture archive path")
                        if (cleanParts.isEmpty()) continue
                        val isIni = cleanParts.last().endsWith(".ini", ignoreCase = true)
                        if (!isIni && !isTextureFile(cleanParts.last())) continue
                        // Keep the archive hierarchy until textures.ini identifies the pack root.
                        val relativeParts = cleanParts
                        val relative = relativeParts.joinToString("/")
                        require(sourceFiles.add(relative)) { "Duplicate texture archive entry" }
                        val stagedTarget = safeChild(sourceRoot, relativeParts)
                            ?: error("Invalid texture archive path")
                        val announcedSize = entry.size.coerceAtLeast(0L)
                        require(announcedSize <= MAX_TEXTURE_FILE_BYTES) { "Texture file is too large" }
                        require(stagingRoot.usableSpace >= announcedSize + MIN_FREE_SPACE_BYTES) {
                            "Not enough free space to import texture archive"
                        }
                        stagedTarget.parentFile?.mkdirs()
                        stagedTarget.outputStream().use { output ->
                            val copied = zip.copyToWithLimit(output, MAX_TEXTURE_FILE_BYTES)
                            totalBytes += copied
                            require(totalBytes <= MAX_ARCHIVE_BYTES) { "Texture archive is too large" }
                        }
                    } finally {
                        zip.closeEntry()
                    }
                }
            }

            val layout = resolveTexturePackLayout(sourceFiles)
            val textureIniEntry = layout.entries.singleOrNull { it.value == "textures.ini" }
            val archiveRoot = sourceFiles.first().substringBefore('/')
            require(layout.values.any { isTextureFile(it.substringAfterLast('/')) }) {
                "Texture pack contains no texture assets"
            }
            val serials = linkedSetOf<String>().apply {
                normalizedTargetSerial?.let(::add)
                if (isEmpty() && textureIniEntry != null) {
                    addAll(readSerialsFromTexturesIni(File(sourceRoot, textureIniEntry.key)))
                }
                if (isEmpty() && textureIniEntry == null) normalizeSerial(archiveRoot)?.let(::add)
                if (isEmpty()) serialFromParts(archiveRoot.split('/'))?.let(::add)
                if (isEmpty()) fallbackSerial?.let(::add)
            }
            require(serials.isNotEmpty()) { "Could not determine a game serial" }

            serials.forEach { serial ->
                val stagedSerialRoot = File(canonicalStagingRoot, gameDirectoryName(serial))
                layout.forEach { (sourceRelative, relative) ->
                    val source = safeChild(sourceRoot, sourceRelative.split('/'))
                        ?: error("Invalid staged texture path")
                    val target = safeChild(stagedSerialRoot, relative.split('/'))
                        ?: error("Invalid staged texture path")
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                }
            }

            if (replaceExisting) {
                serials.forEach { serial ->
                    replacePackAtomically(
                        stagedSerialRoot = File(canonicalStagingRoot, gameDirectoryName(serial)),
                        serial = serial
                    )
                }
                TextureImportResult(
                    success = true,
                    importedFiles = layout.size * serials.size,
                    importedSerials = serials
                )
            } else {
                serials.forEach { serial ->
                    val stagedSerialRoot = File(canonicalStagingRoot, gameDirectoryName(serial))
                    layout.values.forEach { relative ->
                        val staged = safeChild(stagedSerialRoot, relative.split('/'))
                            ?: error("Invalid staged texture path")
                        val target = safeChild(gameDir(serial), relative.split('/'))
                            ?: error("Invalid staged texture path")
                        target.parentFile?.mkdirs()
                        staged.copyTo(target, overwrite = true)
                    }
                }
                TextureImportResult(
                    success = true,
                    importedFiles = layout.size * serials.size,
                    importedSerials = serials
                )
            }
        } catch (_: Exception) {
            TextureImportResult(success = false)
        } finally {
            stagingRoot.deleteRecursively()
        }
    }

    /**
     * Swaps the whole per-serial pack directory in two renames, keeping the
     * previous pack as a rollback until the new one is in place.
     */
    private fun replacePackAtomically(stagedSerialRoot: File, serial: String) {
        val root = texturesRoot().canonicalFile
        val target = File(root, gameDirectoryName(serial)).canonicalFile
        require(target.parentFile == root) { "Invalid texture target" }
        val backup = File(root, ".texture-backup-${UUID.randomUUID()}")
        var oldMoved = false
        try {
            if (target.exists()) {
                require(target.renameTo(backup)) { "Could not prepare texture update" }
                oldMoved = true
            }
            if (!stagedSerialRoot.renameTo(target)) {
                target.mkdirs()
                stagedSerialRoot.walkTopDown()
                    .filter(File::isFile)
                    .forEach { source ->
                        val relative = source.relativeTo(stagedSerialRoot).invariantSeparatorsPath
                        val destination = safeChild(target, relative.split('/'))
                            ?: error("Invalid staged texture path")
                        destination.parentFile?.mkdirs()
                        source.copyTo(destination, overwrite = true)
                    }
            }
            if (backup.exists()) backup.deleteRecursively()
        } catch (error: Throwable) {
            if (target.exists()) target.deleteRecursively()
            if (oldMoved && backup.exists()) backup.renameTo(target)
            throw error
        }
    }

    fun deletePack(serial: String): Boolean {
        val normalized = normalizeSerial(serial) ?: return false
        val directory = existingGameDir(normalized) ?: return false
        return !directory.exists() || directory.deleteRecursively()
    }

    fun clearDumps(serial: String): Boolean {
        val normalized = normalizeSerial(serial) ?: return false
        val game = existingGameDir(normalized) ?: return false
        if (!game.exists()) return true
        val dumps = File(game, "new")
        if (!dumps.exists()) return true
        return dumps.deleteRecursively() && dumps.mkdirs()
    }

    private fun buildPackInfo(folder: File, libraryTitles: Map<String, String>): TexturePackInfo? {
        val serial = normalizeSerial(folder.name) ?: return null
        val dumpDir = File(folder, "new")
        val dumpPrefix = dumpDir.canonicalPath + File.separator
        val allFiles = textureFiles(folder)
        val dumpFiles = allFiles.filter { it.canonicalPath.startsWith(dumpPrefix) }
        val replacementFiles = allFiles.filterNot { it.canonicalPath.startsWith(dumpPrefix) }
        return TexturePackInfo(
            serial = serial,
            gameTitle = libraryTitles[serial],
            replacementCount = replacementFiles.size,
            dumpCount = dumpFiles.size,
            sizeBytes = allFiles.sumOf { it.length() },
            lastModifiedAt = allFiles.maxOfOrNull { it.lastModified() } ?: folder.lastModified()
        )
    }

    private suspend fun loadLibraryTitlesBySerial(): Map<String, String> {
        val paths = preferences.gamePaths.first()
        if (paths.isEmpty()) return emptyMap()
        return libraryCacheRepository
            .loadSnapshot(GameLibraryCacheRepository.libraryKey(paths))
            .games
            .mapNotNull { game ->
                val serial = game.serial?.let(::normalizeSerial) ?: return@mapNotNull null
                val title = game.title.trim().takeIf { it.isNotBlank() && !it.equals(serial, ignoreCase = true) }
                    ?: return@mapNotNull null
                serial to title
            }
            .toMap()
    }

    private fun texturesRoot(): File {
        return EmulatorStorage.texturesDir(context)
    }

    private fun gameDir(serial: String): File {
        return File(texturesRoot(), gameDirectoryName(serial)).apply { mkdirs() }
    }

    private fun existingGameDir(serial: String): File? {
        val root = texturesRoot().canonicalFile
        val target = File(root, gameDirectoryName(serial)).canonicalFile
        return target.takeIf { it.parentFile == root }
    }

    /** Flycast trims the product code and turns spaces into underscores. */
    private fun gameDirectoryName(serial: String): String = serial.trim().replace(' ', '_')

    private fun textureFiles(root: File): List<File> {
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && isTextureFile(it.name) }
            .toList()
    }

    private fun isTextureFile(name: String): Boolean {
        return name.substringAfterLast('.', "").lowercase(Locale.US) in textureExtensions
    }

    private fun readSerialsFromTexturesIni(file: File): Set<String> {
        val gameSection = Regex("^\\s*\\[games\\]\\s*$", RegexOption.IGNORE_CASE)
        val section = Regex("^\\s*\\[[^]]+\\]\\s*$")
        val gameKey = Regex("^\\s*([^=;#]+?)\\s*=")
        var inGames = false
        return file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            buildSet {
                lines.forEach { line ->
                    when {
                        gameSection.matches(line) -> inGames = true
                        section.matches(line) -> inGames = false
                        inGames -> gameKey.find(line)?.groupValues?.getOrNull(1)
                            ?.let(::findSerial)
                            ?.let(::add)
                    }
                }
            }
        }
    }

    private fun cleanZipPath(path: String): List<String>? {
        if (path.startsWith('/') || path.startsWith('\\')) return null
        val rawParts = path.replace('\\', '/').split('/')
        if (rawParts.any { it == ".." || it.contains(':') || it.indexOf('\u0000') >= 0 }) return null
        return rawParts
            .filter { it.isNotEmpty() && it != "." }
    }

    private fun serialFromParts(parts: List<String>): String? {
        return parts.firstNotNullOfOrNull(::findSerial)
    }

    private fun findSerial(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = serialPattern.find(text) ?: return null
        return normalizeSerial(match.value)
    }

    /**
     * Canonical Dreamcast product code. Flycast uses the raw IP.BIN value with
     * trailing whitespace removed and spaces replaced by underscores, so the
     * separator is preserved instead of being reformatted.
     */
    private fun normalizeSerial(raw: String): String? = DiscSerial.normalize(raw)

    private fun safeChild(root: File, relativeParts: List<String>): File? {
        val rootCanonical = root.canonicalFile
        val target = relativeParts.fold(rootCanonical) { current, part -> File(current, part) }.canonicalFile
        return if (target.path.startsWith(rootCanonical.path + File.separator)) target else null
    }

    private fun displayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    } else {
                        null
                    }
                }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private companion object {
        const val MAX_ARCHIVE_ENTRIES = 50_000
        const val MAX_TEXTURE_FILE_BYTES = 512L * 1024L * 1024L
        const val MAX_ARCHIVE_BYTES = 12L * 1024L * 1024L * 1024L
        const val MIN_FREE_SPACE_BYTES = 512L * 1024L * 1024L
    }
}

private fun InputStream.copyToWithLimit(output: java.io.OutputStream, limit: Long): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) return copied
        copied += read
        require(copied <= limit) { "Texture file is too large" }
        output.write(buffer, 0, read)
    }
}
