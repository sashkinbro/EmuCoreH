package com.sbro.emucoreh.data

import java.util.Locale

/**
 * A folder-per-game GD-ROM dump keeps `disc.gdi`/`disc.cue` next to its
 * `trackNN.iso`, `trackNN.bin` or `trackNN.raw` files. Every one of those
 * tracks is part of the disc and must not become a separate library entry,
 * even though `.iso` and `.bin` are otherwise valid standalone content.
 */
internal object DiscTrackReferences {
    private val descriptorExtensions = setOf("cue", "gdi")
    private val trackExtensions = setOf("bin", "img", "iso", "raw")

    fun isDescriptor(name: String): Boolean =
        extension(name) in descriptorExtensions

    fun isTrackCandidate(name: String): Boolean =
        extension(name) in trackExtensions

    /** Paths relative to the scan root, resolved from each descriptor's directory. */
    fun referenced(descriptors: Sequence<Pair<String, String>>): Set<String> =
        descriptors
            .flatMap { (name, text) ->
                val parent = name.replace('\\', '/').split('/').dropLast(1)
                DiscSizeResolver.trackReferences(name, text).asSequence().mapNotNull { reference ->
                    MultiDiscLibrary.resolveRelativeSegments(parent, reference)?.joinToString("/")
                }
            }
            .map(::normalize)
            .filter(String::isNotEmpty)
            .toSet()

    fun isReferenced(name: String, referencedTracks: Set<String>): Boolean =
        isTrackCandidate(name) && normalize(name) in referencedTracks

    /** Providers may expose multiple entries with the same display path. Keep ambiguous matches. */
    fun <T> withoutReferencedTracks(
        entries: List<T>,
        referencedTracks: Set<String>,
        relativePath: (T) -> String
    ): List<T> {
        val paths = entries.map { normalize(relativePath(it)) }
        val counts = paths.groupingBy { it }.eachCount()
        return entries.filterIndexed { index, _ ->
            val path = paths[index]
            counts[path] != 1 || !isReferenced(path, referencedTracks)
        }
    }

    internal fun normalize(name: String): String =
        name.replace('\\', '/')
            .removePrefix("./")
            .trim()
            .lowercase(Locale.ROOT)

    private fun extension(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)
}
