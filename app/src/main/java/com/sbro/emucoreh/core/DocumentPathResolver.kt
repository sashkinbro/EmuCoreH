package com.sbro.emucoreh.core

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.Reader
import androidx.core.net.toUri

object DocumentPathResolver {
    private const val TAG = "DocumentPathResolver"
    private const val BIOS_SOURCE_MARKER = ".source-uri"
    private const val MAX_CUE_TEXT_CHARS = 256 * 1024
    private val cueFileDirective = Regex(
        pattern = """^(\s*)FILE\s+(?:\"([^\"]+)\"|(\S+))\s+(\S+)\s*$""",
        option = RegexOption.IGNORE_CASE
    )

    private data class PreparedDiscResources(
        val launchPath: String,
        val cueText: String,
        val descriptors: List<ParcelFileDescriptor>
    ) : AutoCloseable {
        override fun close() {
            descriptors.forEach { descriptor -> runCatching { descriptor.close() } }
        }
    }

    class PreparedCueLaunch internal constructor(
        val cueText: String,
        val descriptors: IntArray,
        val sizes: LongArray
    )

    private data class DocumentLocation(
        val root: DocumentFile,
        val parentSegments: List<String>
    )

    private val preparedDiscLock = Any()
    private var preparedDiscResources: PreparedDiscResources? = null

    data class PreparedBiosSelection(
        val directoryPath: String,
        val fileName: String?
    )

    private val biosImageExtensions = setOf("bin", "rom", "7d", "8g")
    private val biosArtifactExtensions = setOf("mec", "nvm", "elf")
    private val biosNameHints = listOf("scph", "ps1", "psx", "bios")
    private const val MAX_IMPORTED_BIOS_BYTES = 8L * 1024L * 1024L

    fun resolveFilePath(context: Context, rawPath: String): String? {
        if (!rawPath.startsWith("content://")) return rawPath

        val uri = rawPath.toUri()
        val directPath = resolveExternalStoragePath(uri)
        if (directPath != null) return directPath

        val fileName = DocumentFile.fromSingleUri(context, uri)?.name ?: return null
        return findFileInPersistedTree(context, uri, fileName)
    }

    fun resolveDirectoryPath(rawPath: String): String? {
        if (!rawPath.startsWith("content://")) return rawPath
        return resolveExternalStoragePath(rawPath.toUri())
    }

    fun findAccessibleTreeUriForRawPath(context: Context, rawPath: String): Uri? {
        if (rawPath.startsWith("content://")) return rawPath.toUri()

        val normalizedRawPath = File(rawPath).absolutePath.removeSuffix("/")
        val persistedTrees = context.contentResolver.persistedUriPermissions
            .mapNotNull { permission ->
                val treeUri = permission.uri
                val treePath = resolveExternalStoragePath(treeUri)?.removeSuffix("/") ?: return@mapNotNull null
                treeUri to treePath
            }
            .sortedByDescending { (_, treePath) -> treePath.length }

        for ((treeUri, treePath) in persistedTrees) {
            if (normalizedRawPath != treePath && !normalizedRawPath.startsWith("$treePath/")) {
                continue
            }

            if (normalizedRawPath == treePath) {
                return treeUri
            }

            val root = DocumentFile.fromTreeUri(context, treeUri) ?: continue
            val relativeSegments = normalizedRawPath
                .removePrefix(treePath)
                .trim('/')
                .split('/')
                .filter { it.isNotBlank() }

            var current = root
            var failed = false
            for (segment in relativeSegments) {
                current = current.findFile(segment) ?: run {
                    failed = true
                    break
                }
            }
            if (!failed) {
                return current.uri
            }
        }

        return null
    }

    fun isScopedStorageExternalPath(rawPath: String): Boolean {
        if (rawPath.startsWith("content://")) return false
        val normalized = File(rawPath).absolutePath
        val primaryExternal = Environment.getExternalStorageDirectory().absolutePath
        return normalized.startsWith(primaryExternal)
    }

    fun prepareBiosDirectory(context: Context, rawPath: String?): String? {
        return prepareBiosSelection(context, rawPath)?.directoryPath
    }

    fun prepareBiosSelection(context: Context, rawPath: String?): PreparedBiosSelection? {
        if (rawPath.isNullOrBlank()) return null
        if (!rawPath.startsWith("content://")) {
            val file = File(rawPath)
            return when {
                file.isFile && BiosValidator.hasUsableBiosFiles(context, rawPath) -> PreparedBiosSelection(
                    directoryPath = file.parentFile?.absolutePath ?: file.absoluteFile.parent.orEmpty(),
                    fileName = file.name
                )
                file.isDirectory -> PreparedBiosSelection(
                    directoryPath = file.absolutePath,
                    fileName = findPreferredBiosFileName(file.absolutePath)
                )
                else -> null
            }
        }

        val uri = rawPath.toUri()
        val targetDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "imported-bios")

        val existing = preparedBiosForSource(targetDir, rawPath)
        if (existing != null) return existing

        if (!targetDir.exists() && !targetDir.mkdirs()) return null
        val stagingDir = File(targetDir.parentFile ?: context.filesDir, "imported-bios-staging")

        val imported = runCatching {
            prepareFlatStagingDirectory(stagingDir)
            if (DocumentsContract.isTreeUri(uri)) {
                val root = DocumentFile.fromTreeUri(context, uri) ?: return@runCatching null
                copyBiosFilesRecursive(context, root, stagingDir, ImportBudget())
                val preferred = findPreferredBiosFileName(stagingDir.absolutePath)
                    ?: return@runCatching null
                writeBiosSourceMarker(stagingDir, rawPath)
                if (!replaceImportedBiosDirectory(targetDir, stagingDir)) return@runCatching null
                PreparedBiosSelection(targetDir.absolutePath, preferred)
            } else {
                val single = DocumentFile.fromSingleUri(context, uri) ?: return@runCatching null
                val displayName = runCatching { single.name }.getOrNull().orEmpty().ifBlank {
                    getDisplayName(context, rawPath)
                }
                val copiedPath = copySingleBiosFile(context, single, displayName, stagingDir)
                    ?: return@runCatching null
                val preferred = File(copiedPath).name
                writeBiosSourceMarker(stagingDir, rawPath)
                if (!replaceImportedBiosDirectory(targetDir, stagingDir)) return@runCatching null
                PreparedBiosSelection(targetDir.absolutePath, preferred)
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to prepare BIOS selection: $uri", error)
        }.getOrNull()

        return imported ?: preparedBiosForSource(targetDir, rawPath)
    }

    fun hasPreparedBiosForSource(context: Context, rawPath: String?): Boolean {
        if (rawPath.isNullOrBlank()) return false
        val targetDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "imported-bios")
        return preparedBiosForSource(targetDir, rawPath) != null
    }

    fun findPreferredBiosFileName(directoryPath: String?): String? {
        if (directoryPath.isNullOrBlank()) return null
        val dir = File(directoryPath)
        if (!dir.isDirectory) return null

        return dir.walkTopDown()
            .maxDepth(2)
            .filter(::isValidPreparedBiosFile)
            .sortedWith(
                compareBy<File> {
                    when (it.name.lowercase()) {
                        "r27v1602f.7d" -> 1
                        "r27v1602f.8g" -> 2
                        else -> 0
                    }
                }.thenBy { it.name.lowercase() }
            )
            .firstOrNull()
            ?.name
    }

    fun getDisplayName(context: Context, rawPath: String): String {
        if (!rawPath.startsWith("content://")) return normalizeDisplayName(rawPath)

        val uri = rawPath.toUri()
        val fromResolver = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(0)
                    } else {
                        null
                    }
                }
        }.getOrNull()
        if (!fromResolver.isNullOrBlank()) return normalizeDisplayName(fromResolver, uri)

        val fromSingle = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
        if (!fromSingle.isNullOrBlank()) return normalizeDisplayName(fromSingle, uri)

        val fromTree = runCatching {
            if (DocumentsContract.isTreeUri(uri)) DocumentFile.fromTreeUri(context, uri)?.name else null
        }.getOrNull()
        if (!fromTree.isNullOrBlank()) return normalizeDisplayName(fromTree, uri)

        return normalizeDisplayName(rawPath, uri)
    }

    /**
     * Produces a useful label from a persisted path without querying a DocumentsProvider.
     * Safe for Compose and other main-thread UI code where a provider query can block Binder.
     */
    fun getFallbackDisplayName(rawPath: String): String {
        if (!rawPath.startsWith("content://")) return normalizeDisplayName(rawPath)
        return normalizeDisplayName(rawPath, rawPath.toUri())
    }

    fun prepareElfLaunchPath(context: Context, rawPath: String): String? {
        if (rawPath.isBlank()) return null
        if (!rawPath.startsWith("content://")) return File(rawPath).takeIf { it.isFile && it.canRead() }?.absolutePath ?: rawPath

        val uri = rawPath.toUri()
        val single = DocumentFile.fromSingleUri(context, uri)
        val displayName = single?.name ?: getDisplayName(context, rawPath)
        if (!displayName.substringAfterLast('.', "").equals("elf", ignoreCase = true)) {
            return rawPath
        }

        val directPath = resolveFilePath(context, rawPath)
            ?.let(::File)
            ?.takeIf { it.isFile && it.canRead() }
            ?.absolutePath
        if (!directPath.isNullOrBlank()) return directPath

        return uri.toString()
    }

    fun prepareGameLaunchPath(context: Context, rawPath: String): String? {
        if (rawPath.isBlank()) return null
        if (!rawPath.startsWith("content://")) {
            val direct = File(rawPath)
            if (direct.isFile && direct.canRead()) return direct.absolutePath

            val uri = findAccessibleTreeUriForRawPath(context, rawPath)
                ?: return if (isScopedStorageExternalPath(rawPath)) null else rawPath
            return prepareUriGameLaunchPath(context, uri)
        }

        val uri = rawPath.toUri()
        val directPath = resolveFilePath(context, rawPath)
            ?.let(::File)
            ?.takeIf { it.isFile && it.canRead() }
            ?.absolutePath
        if (!directPath.isNullOrBlank()) return directPath

        return prepareUriGameLaunchPath(context, uri)
    }

    fun releasePreparedLaunchHandles() {
        val resources = synchronized(preparedDiscLock) {
            preparedDiscResources.also { preparedDiscResources = null }
        }
        resources?.close()
    }

    fun getPreparedCueLaunch(launchPath: String): PreparedCueLaunch? =
        synchronized(preparedDiscLock) {
            preparedDiscResources
                ?.takeIf { resources -> resources.launchPath == launchPath }
                ?.let { resources ->
                    PreparedCueLaunch(
                        cueText = resources.cueText,
                        descriptors = resources.descriptors.map { it.fd }.toIntArray(),
                        sizes = resources.descriptors.map { it.statSize.coerceAtLeast(0L) }.toLongArray()
                    )
                }
        }

    private fun prepareUriGameLaunchPath(context: Context, uri: Uri): String? {
        val resolvedDirect = resolveFilePath(context, uri.toString())
            ?.let(::File)
            ?.takeIf { it.isFile && it.canRead() }
            ?.absolutePath
        if (!resolvedDirect.isNullOrBlank()) {
            Log.i(TAG, "Resolved content URI to direct filesystem game path: $resolvedDirect")
            return resolvedDirect
        }

        val displayName = getDisplayName(context, uri.toString())
        if (displayName.substringAfterLast('.', "").equals("cue", ignoreCase = true)) {
            return prepareSafCueLaunch(context, uri, displayName)
        }

        return uri.toString()
    }

    private fun prepareSafCueLaunch(context: Context, cueUri: Uri, displayName: String): String? {
        val cueText = runCatching {
            context.contentResolver.openInputStream(cueUri)
                ?.bufferedReader()
                ?.use(::readLimitedCueText)
                ?: throw IOException("DocumentsProvider returned no CUE stream")
        }.onFailure { error ->
            Log.e(TAG, "Unable to read CUE through SAF: $cueUri", error)
        }.getOrNull() ?: return null

        val references = cueReferences(cueText)
        if (references.isEmpty()) {
            Log.e(TAG, "CUE has no supported FILE ... BINARY directives: $cueUri")
            return null
        }

        val location = findDocumentLocation(context, cueUri)
        if (location == null) {
            Log.e(TAG, "Unable to locate CUE parent inside a persisted SAF tree: $cueUri")
            return null
        }

        val openedDescriptors = ArrayList<ParcelFileDescriptor>(references.size)
        try {
            for (reference in references) {
                val document = resolveDocumentReference(location, reference, context)
                    ?: throw IOException("CUE track is missing or outside the selected tree: $reference")
                val descriptor = context.contentResolver.openFileDescriptor(document.uri, "r")
                    ?: throw IOException("DocumentsProvider returned no descriptor for: $reference")
                if (descriptor.statSize == 0L) {
                    descriptor.close()
                    throw IOException("CUE track is empty: $reference")
                }
                openedDescriptors += descriptor
            }

            val launchPath = cueUri.toString()
            val replacement = PreparedDiscResources(launchPath, cueText, openedDescriptors.toList())
            val previous = synchronized(preparedDiscLock) {
                preparedDiscResources.also { preparedDiscResources = replacement }
            }
            previous?.close()
            Log.i(TAG, "Prepared SAF CUE with ${openedDescriptors.size} track file(s): $displayName")
            return launchPath
        } catch (error: Exception) {
            openedDescriptors.forEach { descriptor -> runCatching { descriptor.close() } }
            Log.e(TAG, "Unable to prepare SAF CUE launch: $cueUri", error)
            return null
        }
    }

    /**
     * Resolves a track reference (as written inside a CUE/GDI descriptor)
     * relative to the descriptor's own folder inside a persisted SAF tree.
     */
    internal fun resolveTrackDocument(context: Context, descriptorUri: Uri, reference: String): DocumentFile? {
        val location = findDocumentLocation(context, descriptorUri) ?: return null
        return resolveDocumentReference(location, reference, context)
    }

    internal fun cueReferences(cueText: String): List<String> {        val references = ArrayList<String>()
        cueText.lineSequence().forEach { line ->
            val match = cueFileDirective.matchEntire(line.removePrefix("\uFEFF"))
                ?: return@forEach
            if (!match.groupValues[4].equals("BINARY", ignoreCase = true)) return@forEach
            val reference = (match.groups[2]?.value ?: match.groups[3]?.value)
                ?.trim()
                .orEmpty()
            if (reference.isNotBlank()) references += reference
        }
        return references
    }

    private fun readLimitedCueText(reader: Reader): String {
        val result = StringBuilder()
        val buffer = CharArray(4096)
        while (result.length <= MAX_CUE_TEXT_CHARS) {
            val remaining = MAX_CUE_TEXT_CHARS + 1 - result.length
            val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            if (count == 0) continue
            result.append(buffer, 0, count)
        }
        if (result.length > MAX_CUE_TEXT_CHARS) {
            throw IOException("CUE exceeds $MAX_CUE_TEXT_CHARS characters")
        }
        return result.toString()
    }

    private fun findDocumentLocation(context: Context, documentUri: Uri): DocumentLocation? {
        val documentId = runCatching { DocumentsContract.getDocumentId(documentUri) }.getOrNull()
            ?: return null
        val parentId = parentDocumentId(documentId) ?: return null
        val persistedTrees = context.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { permission -> permission.isReadPermission && permission.uri.authority == documentUri.authority }
            .mapNotNull { permission ->
                val rootId = runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull()
                    ?: return@mapNotNull null
                val segments = relativeDocumentSegments(rootId, parentId) ?: return@mapNotNull null
                Triple(permission.uri, rootId, segments)
            }
            .sortedByDescending { (_, rootId, _) -> rootId.length }

        for ((treeUri, _, segments) in persistedTrees) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: continue
            return DocumentLocation(root, segments)
        }
        return null
    }

    private fun resolveDocumentReference(
        location: DocumentLocation,
        reference: String,
        context: Context
    ): DocumentFile? {
        if (reference.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(reference)) return null
        val targetSegments = ArrayList(location.parentSegments)
        for (segment in reference.replace('\\', '/').split('/')) {
            when {
                segment.isBlank() || segment == "." -> Unit
                segment == ".." -> {
                    if (targetSegments.isEmpty()) return null
                    targetSegments.removeAt(targetSegments.lastIndex)
                }
                else -> targetSegments += segment
            }
        }
        if (targetSegments.isEmpty()) return null

        var current = location.root
        for ((index, segment) in targetSegments.withIndex()) {
            val child = runCatching { current.listFiles() }
                .getOrDefault(emptyArray())
                .firstOrNull { candidate ->
                    val name = runCatching { candidate.name }.getOrNull().orEmpty().ifBlank {
                        getDisplayName(context, candidate.uri.toString())
                    }
                    name.equals(segment, ignoreCase = true)
                } ?: return null
            if (index == targetSegments.lastIndex) return child.takeIf { it.isFile }
            if (!child.isDirectory) return null
            current = child
        }
        return null
    }

    private fun parentDocumentId(documentId: String): String? {
        val separatorIndex = documentId.lastIndexOf('/')
        if (separatorIndex >= 0) return documentId.substring(0, separatorIndex)
        val volumeSeparatorIndex = documentId.indexOf(':')
        return if (volumeSeparatorIndex >= 0 && volumeSeparatorIndex < documentId.lastIndex) {
            documentId.substring(0, volumeSeparatorIndex + 1)
        } else {
            null
        }
    }

    private fun relativeDocumentSegments(rootDocumentId: String, targetDocumentId: String): List<String>? {
        if (targetDocumentId == rootDocumentId) return emptyList()
        val prefix = if (rootDocumentId.endsWith(':')) rootDocumentId else "$rootDocumentId/"
        if (!targetDocumentId.startsWith(prefix)) return null
        return targetDocumentId.removePrefix(prefix).split('/').filter(String::isNotBlank)
    }

    private fun resolveExternalStoragePath(uri: Uri): String? {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return null

        val parts = Uri.decode(documentId).split(':', limit = 2)
        if (parts.isEmpty()) return null

        val volume = parts[0]
        val relativePath = parts.getOrNull(1).orEmpty()

        return when {
            volume.equals("primary", ignoreCase = true) -> {
                val base = Environment.getExternalStorageDirectory()
                if (relativePath.isBlank()) base.absolutePath
                else File(base, relativePath).absolutePath
            }
            volume.equals("home", ignoreCase = true) -> {
                val base = File(Environment.getExternalStorageDirectory(), "Documents")
                if (relativePath.isBlank()) base.absolutePath
                else File(base, relativePath).absolutePath
            }
            volume.equals("raw", ignoreCase = true) && relativePath.startsWith("/") -> relativePath
            volume.startsWith("/") -> volume
            else -> {
                val base = File("/storage", volume)
                if (relativePath.isBlank()) base.absolutePath
                else File(base, relativePath).absolutePath
            }
        }
    }

    private fun findFileInPersistedTree(context: Context, targetUri: Uri, fileName: String): String? {
        val persistedTrees = context.contentResolver.persistedUriPermissions
            .mapNotNull { permission -> DocumentFile.fromTreeUri(context, permission.uri) }

        for (tree in persistedTrees) {
            val resolved = findFileRecursive(tree, targetUri, fileName)
            if (resolved != null) return resolved
        }

        return null
    }

    private fun findFileRecursive(root: DocumentFile, targetUri: Uri, fileName: String): String? {
        for (child in root.listFiles()) {
            if (child.uri == targetUri) {
                return resolveExternalStoragePath(child.uri)
            }

            if (child.isDirectory) {
                val nested = findFileRecursive(child, targetUri, fileName)
                if (nested != null) return nested
            } else if (child.name == fileName) {
                val direct = resolveExternalStoragePath(child.uri)
                if (direct != null) return direct
            }
        }

        return null
    }

    fun normalizeDisplayName(rawName: String): String {
        return normalizeDisplayName(rawName, null)
    }

    private fun normalizeDisplayName(rawName: String, uri: Uri?): String {
        val candidates = buildList {
            add(rawName)
            if (uri != null) {
                runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()?.let(::add)
                runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()?.let(::add)
                uri.lastPathSegment?.let(::add)
            }
        }

        for (candidate in candidates) {
            val name = extractDisplayLeaf(candidate)
            if (name.isNotBlank()) return name
        }

        return rawName
    }

    private fun extractDisplayLeaf(candidate: String): String {
        val decoded = Uri.decode(candidate).orEmpty().trim()
        if (decoded.isBlank()) return ""

        val withoutRawPrefix = decoded.removePrefix("raw:")
        val documentPart = withoutRawPrefix.substringAfter("/document/", withoutRawPrefix)
        val treePart = documentPart.substringAfter("/tree/", documentPart)
        val storagePart = when {
            treePart.startsWith("primary:", ignoreCase = true) -> treePart.substringAfter(':')
            treePart.startsWith("home:", ignoreCase = true) -> treePart.substringAfter(':')
            treePart.indexOf(':') > 0 && treePart.substringAfter(':').contains('/') -> treePart.substringAfter(':')
            else -> treePart
        }
        return storagePart.substringAfterLast('/').trim()
    }

    private fun copyBiosFilesRecursive(
        context: Context,
        root: DocumentFile,
        targetDir: File,
        budget: ImportBudget
    ) {
        if (!budget.tryEnterDirectory()) return
        for (child in runCatching { root.listFiles() }.getOrDefault(emptyArray())) {
            val mimeType = runCatching { child.type }.getOrNull()
            val displayName = runCatching { child.name }.getOrNull().orEmpty().ifBlank {
                getDisplayName(context, child.uri.toString())
            }
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                copyBiosFilesRecursive(context, child, targetDir, budget)
            } else if (isLikelyImportedBiosName(displayName)) {
                if (!budget.tryCopyFile()) return
                val targetFile = File(targetDir, sanitizeFileName(displayName))
                copyUriToFile(context, child.uri, targetFile)
            } else if (mimeType == null) {
                copyBiosFilesRecursive(context, child, targetDir, budget)
            }
        }
    }

    private fun copySingleBiosFile(
        context: Context,
        file: DocumentFile,
        displayName: String,
        targetDir: File
    ): String? {
        if (displayName.substringAfterLast('.', "").lowercase() !in biosImageExtensions) return null

        val targetFile = File(targetDir, sanitizeFileName(displayName))
        val copiedPath = copyUriToFile(context, file.uri, targetFile) ?: return null
        if (!isValidPreparedBiosFile(targetFile)) {
            targetFile.delete()
            return null
        }
        return copiedPath
    }

    private fun copyUriToFile(context: Context, uri: Uri, targetFile: File): String? {
        return runCatching {
            targetFile.parentFile?.mkdirs()
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_IMPORTED_BIOS_BYTES) {
                            throw IOException("BIOS import exceeds $MAX_IMPORTED_BIOS_BYTES bytes")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            targetFile.absolutePath
        }.onFailure { error ->
            targetFile.delete()
            Log.w(TAG, "Failed to copy $uri to ${targetFile.absolutePath}", error)
        }.getOrNull()
    }

    private fun isLikelyImportedBiosName(name: String?): Boolean {
        val fileName = name?.lowercase() ?: return false
        val ext = fileName.substringAfterLast('.', "")
        return ext in biosImageExtensions ||
            (ext in biosArtifactExtensions && biosNameHints.any(fileName::contains))
    }

    private fun isLikelyMainBiosName(name: String?): Boolean {
        val fileName = name?.lowercase() ?: return false
        val ext = fileName.substringAfterLast('.', "")
        return ext in biosImageExtensions && biosNameHints.any(fileName::contains)
    }

    private fun isValidPreparedBiosFile(file: File): Boolean {
        if (!file.isFile || file.extension.lowercase() !in biosImageExtensions) return false
        if (file.length() <= 0L || file.length() > MAX_IMPORTED_BIOS_BYTES) return false
        return if (NativeApp.hasNativeCore) {
            runCatching { NativeApp.isBiosPath(file.absolutePath) }.getOrDefault(false) ||
                isLikelyMainBiosName(file.name)
        } else {
            isLikelyMainBiosName(file.name)
        }
    }

    private fun prepareFlatStagingDirectory(stagingDir: File) {
        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            throw IOException("Unable to create BIOS staging directory")
        }
        stagingDir.listFiles().orEmpty().forEach { file ->
            if (file.isFile) file.delete()
        }
    }

    private fun writeBiosSourceMarker(directory: File, rawPath: String) {
        File(directory, BIOS_SOURCE_MARKER).writeText(rawPath)
    }

    private fun preparedBiosForSource(targetDir: File, rawPath: String): PreparedBiosSelection? {
        val markerMatches = runCatching {
            File(targetDir, BIOS_SOURCE_MARKER).readText() == rawPath
        }.getOrDefault(false)
        if (!markerMatches) return null
        val preferred = findPreferredBiosFileName(targetDir.absolutePath) ?: return null
        return PreparedBiosSelection(targetDir.absolutePath, preferred)
    }

    internal fun replaceImportedBiosDirectory(targetDir: File, stagingDir: File): Boolean {
        val parent = targetDir.parentFile ?: return false
        val backupDir = File(parent, "imported-bios-backup")
        prepareFlatStagingDirectory(backupDir)
        backupDir.delete()

        val hadTarget = targetDir.exists()
        if (hadTarget && !targetDir.renameTo(backupDir)) return false
        if (!stagingDir.renameTo(targetDir)) {
            if (hadTarget) backupDir.renameTo(targetDir)
            return false
        }

        val preserveExtensions = setOf("nvm", "mec")
        backupDir.listFiles().orEmpty().forEach { file ->
            if (file.isFile) {
                if (file.extension.lowercase() in preserveExtensions) {
                    val targetFile = File(targetDir, file.name)
                    if (!targetFile.exists()) file.copyTo(targetFile, overwrite = false)
                }
                file.delete()
            }
        }
        backupDir.delete()
        return true
    }

    private class ImportBudget {
        private var files = 0
        private var directories = 0

        fun tryCopyFile(): Boolean = files++ < 24
        fun tryEnterDirectory(): Boolean = directories++ < 96
    }

    private fun sanitizeFileName(name: String): String {
        return buildString(name.length) {
            name.forEach { ch ->
                append(
                    when {
                        ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_' -> ch
                        else -> '_'
                    }
                )
            }
        }
    }
}
