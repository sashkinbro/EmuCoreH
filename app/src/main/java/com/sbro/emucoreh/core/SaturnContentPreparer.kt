package com.sbro.emucoreh.core

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Beetle Saturn needs a cue-sheet for raw ISO/BIN images. The shared Sega
 * materializer still handles SAF descriptors and CUE/M3U rewriting.
 */
object SaturnContentPreparer {
    private const val CUE_DIRECTORY = "saturn-cues"
    private val preparedCues = mutableSetOf<File>()

    fun prepare(context: Context, path: String): String {
        val prepared = JGenesisContentPreparer.prepare(context, path)
        val image = File(prepared)
        if (image.extension.lowercase(Locale.ROOT) !in setOf("iso", "bin")) return prepared
        val cue = cueFor(context, path, image)
        synchronized(preparedCues) { preparedCues += cue }
        return cue.absolutePath
    }

    fun releasePreparedContent(context: Context): Boolean {
        val released = JGenesisContentPreparer.releasePreparedContent(context)
        synchronized(preparedCues) {
            preparedCues.forEach { it.delete() }
            preparedCues.clear()
        }
        return released
    }

    fun cleanupAbandonedContent(context: Context): Boolean {
        val cleaned = JGenesisContentPreparer.cleanupAbandonedContent(context)
        File(context.cacheDir, CUE_DIRECTORY).listFiles()?.forEach { it.delete() }
        return cleaned
    }

    private fun cueFor(context: Context, sourcePath: String, image: File): File {
        val directory = File(context.cacheDir, CUE_DIRECTORY).apply { mkdirs() }
        // The prepared SAF copy gets a new session directory on every launch; derive
        // the cue identity from the stable source path so Beetle's per-content save
        // names do not change between sessions.
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$sourcePath|${image.length()}".toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        val displayName = if (sourcePath.startsWith("content://")) {
            DocumentPathResolver.getDisplayName(context, sourcePath) ?: image.name
        } else image.name
        val name = displayName.substringBeforeLast('.').replace('/', '_').replace('\\', '_')
            .take(48).ifBlank { "disc" }
        val cue = File(directory, "$name-$digest.cue")
        val path = image.absolutePath.replace('\\', '/').replace("\"", "\\\"")
        val cueText = "FILE \"$path\" BINARY\n" +
            "  TRACK 01 ${trackMode(image)}\n" +
            "    INDEX 01 00:00:00\n"
        cue.writeText(cueText)
        return cue
    }

    private fun trackMode(image: File): String {
        val size = image.length()
        if (size > 0 && size % 2352L == 0L && hasRawSectorSync(image)) return "MODE1/2352"
        if (size > 0 && size % 2048L == 0L) return "MODE1/2048"
        return "MODE1/2352"
    }

    private fun hasRawSectorSync(image: File): Boolean = runCatching {
        val header = ByteArray(12)
        image.inputStream().use { input ->
            if (input.read(header) != header.size) return false
        }
        header[0] == 0.toByte() && (1 until 11).all { header[it] == 0xFF.toByte() } && header[11] == 0.toByte()
    }.getOrDefault(false)
}
