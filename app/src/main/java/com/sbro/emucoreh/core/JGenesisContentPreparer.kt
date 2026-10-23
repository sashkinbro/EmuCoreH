package com.sbro.emucoreh.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/** Materializes SAF content because jgenesis uses ordinary filesystem I/O. */
object JGenesisContentPreparer {
    private const val MAX_DESCRIPTOR_BYTES = 1024 * 1024
    private const val MAX_FILES = 256
    private const val MAX_DEPTH = 8
    private val fileDirective = Regex("""^(\s*FILE\s+)(?:"([^"]+)"|(.+?))(\s+\S+\s*)$""", RegexOption.IGNORE_CASE)
    private val caches = HashMap<String, JGenesisContentCache>()

    fun prepare(context: Context, path: String): String {
        if (!path.startsWith("content://")) return prepareFile(File(path))
        return try {
            cacheFor(context).prepare(path, object : JGenesisContentSource {
                override fun displayName(path: String): String = DocumentPathResolver.getDisplayName(context, path)
                override fun open(path: String) = context.contentResolver.openInputStream(Uri.parse(path))
                override fun resolveReference(path: String, reference: String): String? =
                    DocumentPathResolver.resolveTrackDocument(context, Uri.parse(path), reference)?.uri?.toString()
            }).absolutePath
        } catch (error: Exception) {
            throw IOException("Unable to prepare Sega content: ${error.message}", error)
        }
    }

    /** Call only after the native core has unloaded; swaps retain old media until this point. */
    fun releasePreparedContent(context: Context): Boolean = cacheFor(context).release()

    /** Startup cleanup; tracked active content is preserved even if called again later. */
    fun cleanupAbandonedContent(context: Context): Boolean = cacheFor(context).cleanupAbandoned()

    private fun cacheFor(context: Context): JGenesisContentCache = synchronized(caches) {
        val directory = context.applicationContext.cacheDir
        caches.getOrPut(directory.canonicalPath) { JGenesisContentCache(directory) }
    }

    fun prepareFile(file: File): String = prepareFile(file, HashSet(), 0).canonicalPath

    private fun prepareFile(file: File, active: MutableSet<String>, depth: Int): File {
        require(depth <= MAX_DEPTH && active.add(file.canonicalPath)) { "Recursive or overly nested playlist" }
        try {
            require(file.isFile && file.canRead()) { "Content file is missing or unreadable: ${file.name}" }
            if (!file.extension.equals("m3u", true)) return file
            require(file.length() <= MAX_DESCRIPTOR_BYTES) { "Playlist is too large" }
            val references = SegaPlatformDetector.descriptorReferences(file.name, file.readText())
            require(references.isNotEmpty() && references.size <= MAX_FILES) { "Playlist has no discs or too many entries" }
            return references.map { reference ->
                val target = SegaPlatformDetector.safeLocalReference(file, reference)
                    ?: throw IOException("Playlist member is missing or outside its directory: $reference")
                prepareFile(target, active, depth + 1)
            }.first()
        } finally { active.remove(file.canonicalPath) }
    }

    internal fun rewriteCue(text: String, fileNameFor: (String) -> String): String = text.lineSequence().joinToString("\n") { line ->
        val match = fileDirective.matchEntire(line) ?: return@joinToString line
        val reference = (match.groups[2]?.value ?: match.groups[3]?.value).orEmpty().trim()
        "${match.groupValues[1]}\"${fileNameFor(reference)}\"${match.groupValues[4]}"
    }

    internal fun cueReferences(text: String): List<String> = text.lineSequence().mapNotNull { line ->
        val match = fileDirective.matchEntire(line.removePrefix("\uFEFF")) ?: return@mapNotNull null
        (match.groups[2]?.value ?: match.groups[3]?.value)?.trim()?.takeIf(String::isNotEmpty)
    }.distinct().toList()

    internal fun requireSafeReference(reference: String) {
        val normalized = reference.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') &&
            !Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(normalized) && !normalized.contains('\u0000')) {
            "Absolute paths and URLs are not allowed in descriptors"
        }
    }
}
