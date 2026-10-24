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

    /** Every track name referenced by the supplied descriptors, lower-cased. */
    fun referenced(descriptors: Sequence<Pair<String, String>>): Set<String> =
        descriptors
            .flatMap { (name, text) -> DiscSizeResolver.trackReferences(name, text).asSequence() }
            .map(::normalize)
            .filter(String::isNotEmpty)
            .toSet()

    fun isReferenced(name: String, referencedTracks: Set<String>): Boolean =
        isTrackCandidate(name) && normalize(name) in referencedTracks

    internal fun normalize(name: String): String =
        name.replace('\\', '/')
            .substringAfterLast('/')
            .trim()
            .lowercase(Locale.ROOT)

    private fun extension(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)
}
