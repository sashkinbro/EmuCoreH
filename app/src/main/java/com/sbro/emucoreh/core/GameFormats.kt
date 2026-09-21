package com.sbro.emucoreh.core

import java.util.Locale

/** Content candidates accepted by the Flycast core (disc images and arcade ROMs). */
object GameFormats {
    /** Disc images for Dreamcast, Naomi, Naomi 2 and Atomiswave. */
    val discExtensions = setOf("gdi", "chd", "cdi", "cue", "iso")

    /**
     * Naomi/Atomiswave cartridge ROM sets and archive containers. Raw `.bin`
     * files are deliberately excluded: in a CUE/BIN disc set every track is a
     * `.bin`, and listing the tracks as separate games hides the real entries.
     */
    val romExtensions = setOf("dat", "lst", "zip", "7z")

    val extensions = discExtensions + romExtensions

    val archives = setOf("zip", "7z")

    fun isSupportedName(name: String): Boolean {
        val fileName = name.substringAfterLast('/').lowercase(Locale.ROOT)
        return fileName.substringAfterLast('.', "") in extensions
    }

    fun isArchiveName(name: String): Boolean {
        val fileName = name.substringAfterLast('/').lowercase(Locale.ROOT)
        return fileName.substringAfterLast('.', "") in archives
    }
}
