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

    /** Multi-disc playlists: the core reads the entries and exposes the discs. */
    val playlistExtensions = setOf("m3u")

    /** Standalone executables (homebrew) supported by the core. */
    val executableExtensions = setOf("elf")

    val extensions = discExtensions + romExtensions + playlistExtensions + executableExtensions

    val archives = setOf("zip", "7z")

    fun isSupportedName(name: String): Boolean = extensionOf(name) in extensions

    /**
     * The library hides raw tracks, but a file picked for a direct launch may
     * be any content the core accepts, including a lone `.bin`.
     */
    fun isLaunchableName(name: String): Boolean = isSupportedName(name) || extensionOf(name) == "bin"

    fun isArchiveName(name: String): Boolean = extensionOf(name) in archives

    private fun extensionOf(name: String): String =
        name.substringAfterLast('/').lowercase(Locale.ROOT).substringAfterLast('.', "")
}
