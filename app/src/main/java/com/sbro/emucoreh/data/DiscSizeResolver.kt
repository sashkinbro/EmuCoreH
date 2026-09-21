package com.sbro.emucoreh.data

import java.util.Locale

/**
 * Works out how large a multi-file disc image really is.
 *
 * `.cue`, `.gdi` and `.lst` files are only a few hundred bytes: the audio and
 * data tracks they reference hold the actual game. The library listing sums
 * every referenced track so the reported size matches what the disc costs on
 * storage. `.m3u` playlists add the size of each disc they list.
 */
object DiscSizeResolver {
    private val containerExtensions = setOf("cue", "gdi", "lst", "m3u")
    private val cueFilePattern = Regex("FILE\\s+\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val cueFileSingleQuotePattern = Regex("FILE\\s+'([^']+)'", RegexOption.IGNORE_CASE)
    private const val MAX_DEPTH = 2

    /**
     * @param entryName name of the scanned entry (the `.cue`/`.gdi`/`.m3u` file)
     * @param sizeOf    resolves a sibling's size in bytes, or null when unknown
     * @param readText  reads a sibling as text; null when it cannot be read
     * @return the summed size in bytes, or null when it cannot be resolved
     */
    fun resolve(
        entryName: String,
        sizeOf: (String) -> Long?,
        readText: (String) -> String?
    ): Long? = resolve(entryName, sizeOf, readText, 0)

    private fun resolve(
        entryName: String,
        sizeOf: (String) -> Long?,
        readText: (String) -> String?,
        depth: Int
    ): Long? {
        if (depth > MAX_DEPTH) return null
        val extension = entryName.substringAfterLast('.', "").lowercase(Locale.US)
        if (extension !in containerExtensions) return null
        val contents = readText(entryName) ?: return null
        val referenced = when (extension) {
            "m3u" -> parseM3u(contents)
            "cue" -> parseCue(contents)
            else -> parseTrackList(contents)
        }
        if (referenced.isEmpty()) return null

        var total = sizeOf(entryName) ?: 0L
        var matched = false
        for (name in referenced) {
            val size = sizeOf(name)
            if (size != null) {
                matched = true
                total += size
                continue
            }
            resolve(name, sizeOf, readText, depth + 1)?.let { nested ->
                matched = true
                total += nested
            }
        }
        return if (matched) total else null
    }

    private fun parseCue(contents: String): List<String> =
        contents.lineSequence().mapNotNull { line ->
            val quoted = cueFilePattern.find(line) ?: cueFileSingleQuotePattern.find(line)
            quoted?.groupValues?.getOrNull(1)?.let(::baseName)?.takeIf { it.isNotEmpty() }
        }.toList()

    /** `.gdi` and `.lst` lines look like `1 0 4 2352 track01.bin 0`. */
    private fun parseTrackList(contents: String): List<String> =
        contents.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
            val tokens = trimmed.split(Regex("\\s+"))
            if (tokens.size < 5) return@mapNotNull null
            val raw = tokens[4]
            val name = if (raw.startsWith('"')) {
                val open = trimmed.indexOf('"')
                val close = trimmed.indexOf('"', open + 1)
                if (close > open) trimmed.substring(open + 1, close) else raw
            } else {
                raw
            }
            name.let(::baseName).takeIf { it.isNotEmpty() }
        }.toList()

    private fun parseM3u(contents: String): List<String> =
        contents.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map(::baseName)
            .filter { it.isNotEmpty() }
            .toList()

    private fun baseName(path: String): String = path
        .replace('\\', '/')
        .substringAfterLast('/')
        .trim()
}
