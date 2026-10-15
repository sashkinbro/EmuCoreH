package com.sbro.emucoreh.data.boxart

import java.io.File
import java.io.IOException

/** Invalidates only the incorrect arcade assets replaced at their existing repository URLs. */
internal object ArcadeLandscapeCoverRefresh {
    private const val MARKER = ".arcade_landscape_v1.marker"
    private val replacedStems = setOf(
        "anpanman2", "bingogal", "brickppl", "derbyo2k", "derbyoc", "derbyoc2",
        "derbyocw", "dragntr", "dragntr2", "dragntr3", "drbyocwa", "galilfac",
        "hopper", "huhimage", "mj1", "monomedal", "oinori", "shors2k1", "shors2k2",
        "shorse", "shorsep", "shorsepr"
    )
    private val sourcePattern = Regex(
        "https://raw\\.githubusercontent\\.com/sashkinbro/EmuCoreH-Covers/main/arcade/" +
            "(?:3d/(?:light/)?)?([a-z0-9]+)\\.(?:jpg|jpeg|png|webp)"
    )

    /** Caller must gate this on an online fetch; offline sessions retain their existing artwork. */
    @Synchronized
    fun refresh(directory: File): Boolean {
        val marker = File(directory, MARKER)
        if (marker.exists()) return false
        return runCatching {
            val files = directory.listFiles() ?: throw IOException("Cannot list cover cache")
            val keys = files.asSequence().filter { it.isFile && it.extension == "url" }
                .filter { source ->
                    sourcePattern.matchEntire(source.readText().trim())?.groupValues?.get(1) in replacedStems
                }
                .map { it.name.removeSuffix(".url").removeSuffix(".light").removeSuffix(".dark") }
                .toSet()
            var changed = false
            for (key in keys) {
                val suffixes = listOf(
                    ".online.png", ".online.jpg", ".online.jpeg", ".online.webp",
                    ".light.online.webp", ".dark.online.webp", ".jpg", ".jpeg", ".webp",
                    ".light.webp", ".dark.webp", "_light.webp", "_dark.webp"
                )
                // Never remove legacy key.png or key.disc.png: these may be extracted GD art.
                for (suffix in suffixes) {
                    val image = File(directory, "$key$suffix")
                    if (image.exists()) {
                        if (!image.delete()) throw IOException("Cannot remove replaced cover: ${image.name}")
                        changed = true
                    }
                }
                // Retain provenance on image-deletion failure so a later attempt can still identify it.
                for (suffix in listOf(".url", ".light.url", ".dark.url")) {
                    val source = File(directory, "$key$suffix")
                    if (source.exists() && !source.delete()) throw IOException("Cannot remove cover provenance")
                }
            }
            if (!marker.createNewFile()) throw IOException("Cannot mark cover refresh complete")
            changed
        }.getOrDefault(false)
    }
}
