package com.sbro.emucoreh.core

import java.util.Locale

/** Content candidates accepted by the bundled emulator cores. */
object GameFormats {
    /** Disc images for Dreamcast, Naomi, Naomi 2 and Atomiswave. */
    val discExtensions = setOf("gdi", "chd", "cdi", "cue", "iso")

    /**
     * Naomi/Atomiswave cartridge ROM sets and archive containers. Raw `.bin`
     * files are deliberately excluded: in a CUE/BIN disc set every track is a
     * `.bin`, and listing the tracks as separate games hides the real entries.
     */
    val romExtensions = setOf("dat", "lst", "zip", "7z")

    val segaCartridgeExtensions = setOf("md", "gen", "smd", "32x", "sms", "gg", "sg")

    /** Multi-disc playlists: the core reads the entries and exposes the discs. */
    val playlistExtensions = setOf("m3u")

    val extensions = discExtensions + romExtensions + segaCartridgeExtensions + playlistExtensions

    val archives = setOf("zip", "7z")

    /**
     * Display labels for the Supported Formats screen. They mirror the
     * extension sets above so the screen can never advertise a format the
     * frontend does not scan or the core does not open.
     */
    val discFormatLabels = listOf("GDI", "CHD", "CDI", "CUE/BIN", "ISO")
    val playlistFormatLabels = listOf("M3U")
    /** Zip/7z sets plus the legacy `.bin`/`.dat`/`.lst` Naomi dumps. */
    val arcadeFormatLabels = listOf("ZIP", "7Z", "BIN", "DAT", "LST")
    val segaFormatLabels = listOf("MD", "GEN", "SMD", "32X", "SMS", "GG", "SG", "BIN", "ZIP")

    /**
     * MIME filter for the system file picker. Providers report unknown
     * extensions such as `.gdi` or `.chd` as `application/octet-stream`, so
     * that entry keeps them selectable while unrelated media stays hidden.
     */
    val launchMimeTypes = arrayOf(
        "application/octet-stream",
        "application/x-iso9660-image",
        "application/x-cd-image",
        "application/x-chd",
        "application/x-cdi",
        "application/x-gdi",
        "application/x-cue",
        "application/x-genesis-rom",
        "application/x-sms-rom",
        "application/x-gamegear-rom",
        "application/x-7z-compressed",
        "application/zip",
        "audio/x-mpegurl",
        // Providers report legacy Naomi `.lst`/`.dat` sets as plain text.
        "text/plain"
    )

    fun isSupportedName(name: String): Boolean = extensionOf(name) in extensions

    /** A replacement GD-ROM image; arcade ROMs and playlists require a new launch. */
    fun isDiscImageName(name: String): Boolean = extensionOf(name) in discExtensions

    /**
     * The library hides raw tracks, but a file picked for a direct launch may
     * be any content the core accepts, including a lone `.bin`.
     */
    fun isLaunchableName(name: String): Boolean = isSupportedName(name) || extensionOf(name) == "bin"

    fun isArchiveName(name: String): Boolean = extensionOf(name) in archives

    private fun extensionOf(name: String): String =
        name.substringAfterLast('/').lowercase(Locale.ROOT).substringAfterLast('.', "")
}
