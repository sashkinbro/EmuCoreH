// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.zip.CRC32

/**
 * Dreamcast and arcade BIOS files. Flycast reads them from `<system>/dc/`:
 * `dc_boot.bin` (2 MiB) or `dc_bios.bin`, plus the 128 KiB flash ROM, which the
 * core names `dc_nvmem.bin` (`dc_flash.bin` is the standalone Flycast name and
 * stays accepted on import). Arcade titles load their BIOS from the MAME sets
 * (`naomi.zip`, `naomi2.zip`, `awbios.zip` and the game-specific sets).
 * Games boot without a console dump through the core's HLE BIOS, which the
 * frontend enables automatically when no dump is installed.
 */
object DreamcastBios {
    private const val TAG = "DreamcastBios"
    const val BOOT_ROM_SIZE = 2L * 1024L * 1024L
    const val FLASH_ROM_SIZE = 128L * 1024L
    val bootFileNames = listOf("dc_boot.bin", "dc_bios.bin")

    /**
     * Name the libretro core uses for the flash ROM (`dc_` prefix + `nvmem.bin`
     * in its own lookup) and the standalone name still accepted on import.
     */
    const val FLASH_FILE_NAME = "dc_nvmem.bin"
    const val LEGACY_FLASH_FILE_NAME = "dc_flash.bin"
    val flashFileNames = listOf(FLASH_FILE_NAME, LEGACY_FLASH_FILE_NAME)

    /** MAME BIOS set names the core loads for Naomi, Naomi 2 and Atomiswave. */
    val arcadeBiosNames = setOf(
        "airlbios",
        "anpanman2",
        "awbios",
        "bingogal",
        "bingogals",
        "brickppl",
        "clubk2k3",
        "clubkcyc",
        "clubkpzb",
        "clubkrt",
        "deathcox",
        "dinoki25",
        "dinoki4",
        "dinokich",
        "dinoking",
        "dinokior",
        "dygolf",
        "f355bios",
        "f355dlx",
        "galilfac",
        "hod2bios",
        "hopper",
        "inidv3cy",
        "initd",
        "initdexp",
        "initdv2j",
        "initdv3j",
        "isshoni",
        "kingrt66",
        "kingyo",
        "loveber3",
        "lovebero",
        "lovebery",
        "manicpnc",
        "mushi2k61",
        "mushi2k62",
        "naomi",
        "naomi2",
        "naomidev",
        "naomigd",
        "puyofev",
        "segasp",
        "tetgiant",
        "tokyobus",
        "vf4",
        "vf4evo",
        "vf4tuned",
        "vstrik3c",
        "wccf116",
        "wccf1dup",
        "wccf212e",
        "wccf234j",
        "wccf310j",
        "wccf322e",
        "wccf331e",
        "wccf331j",
        "wccf341j",
        "wccf400j",
        "wccf420e",
        "zombrvn",
        "zombrvne"
    )

    private val arcadeArchiveExtensions = setOf("zip", "7z")
    private val arcadeBootRomMarkers = listOf("NAOMI", "ATOMISWAVE")
    private const val MAX_BOOT_ROM_SCAN_BYTES = 2 * 1024 * 1024
    private const val MAX_BIOS_BYTES = 64L * 1024L * 1024L
    private const val MAX_PROBE_DIRECTORIES = 64
    private const val MAX_PROBE_FILES = 256

    fun directory(systemDir: String): File = File(systemDir, "dc")

    fun findBootRom(systemDir: String): File? = bootFileNames
        .map { File(directory(systemDir), it) }
        .firstOrNull { it.isFile && it.length() == BOOT_ROM_SIZE }

    fun findFlashRom(systemDir: String): File? = flashFileNames
        .map { File(directory(systemDir), it) }
        .firstOrNull { it.isFile && it.length() == FLASH_ROM_SIZE }

    /**
     * The core reads and writes the flash ROM as `dc_nvmem.bin`. Dumps and older
     * installs use the standalone name `dc_flash.bin`, so a legacy file is
     * copied once under the name the core actually opens.
     */
    fun migrateLegacyFlash(systemDir: String): Boolean {
        val dir = directory(systemDir)
        val canonical = File(dir, FLASH_FILE_NAME)
        if (canonical.isFile) return false
        val legacy = File(dir, LEGACY_FLASH_FILE_NAME)
        if (!legacy.isFile || legacy.length() != FLASH_ROM_SIZE) return false
        return runCatching {
            legacy.copyTo(canonical, overwrite = false)
            canonical.isFile
        }.getOrDefault(false)
    }

    fun hasBootRom(systemDir: String): Boolean = findBootRom(systemDir) != null

    /**
     * Naomi and Atomiswave boot ROMs are 2 MiB as well, so a dump recognized
     * only by its size can end up installed as the Dreamcast BIOS. Those
     * firmwares carry their own product names, which a Dreamcast boot ROM never
     * contains; treating such a dump as absent keeps the core on HLE instead of
     * booting arcade firmware on Dreamcast hardware.
     */
    internal fun looksLikeArcadeBootRom(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val text = String(bytes, Charsets.ISO_8859_1)
        return arcadeBootRomMarkers.any { marker -> text.contains(marker, ignoreCase = true) }
    }

    fun looksLikeArcadeBootRom(file: File): Boolean = runCatching {
        if (!file.isFile) return false
        file.inputStream().use { stream ->
            val buffer = ByteArray(MAX_BOOT_ROM_SCAN_BYTES)
            var filled = 0
            while (filled < buffer.size) {
                val read = stream.read(buffer, filled, buffer.size - filled)
                if (read <= 0) break
                filled += read
            }
            looksLikeArcadeBootRom(buffer.copyOf(filled))
        }
    }.getOrDefault(false)

    /** CRC32 of an installed boot ROM, for the launch diagnostics line. */
    fun bootRomFingerprint(file: File?): String {
        val rom = file?.takeIf { it.isFile } ?: return "none"
        return runCatching {
            val crc = CRC32()
            rom.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    crc.update(buffer, 0, read)
                }
            }
            "%08x".format(crc.value)
        }.getOrDefault("?")
    }

    /** True when the picked file carries the canonical BIOS name already. */
    private fun isExplicitBiosName(name: String?): Boolean {
        val fileName = name?.lowercase()?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')
            ?: return false
        return fileName in bootFileNames || fileName in flashFileNames
    }

    /** True for the Dreamcast boot/flash ROMs (the two size-guessed targets). */
    private fun isConsoleRomName(name: String): Boolean =
        name in bootFileNames || name in flashFileNames

    /** True when at least one Naomi, Naomi 2 or Atomiswave BIOS set is installed. */
    fun hasArcadeBios(systemDir: String): Boolean = arcadeBiosNames.any { name ->
        arcadeArchiveExtensions.any { extension ->
            File(directory(systemDir), "$name.$extension").isFile
        }
    }

    /** True when any supported console or arcade BIOS is installed. */
    fun hasAnyBios(systemDir: String): Boolean =
        hasBootRom(systemDir) || findFlashRom(systemDir) != null || hasArcadeBios(systemDir)

    /**
     * Installs a user-picked BIOS selection into `<system>/dc/`. Folder picks
     * copy every supported BIOS file they contain, single-file picks keep the
     * legacy Dreamcast dump handling.
     */
    fun installSelection(context: Context, uri: Uri, systemDir: String): Boolean =
        if (DocumentsContract.isTreeUri(uri)) {
            installFromDirectory(context, uri, systemDir)
        } else {
            install(context, uri, systemDir)
        }

    /**
     * Copies a user-picked BIOS dump into `<system>/dc/`. The destination name
     * is derived from the file name or, for unnamed dumps, the file size:
     * 2 MiB is the boot ROM, 128 KiB the flash ROM. Returns true when a BIOS
     * file was installed.
     */
    fun install(context: Context, uri: Uri, systemDir: String): Boolean {
        import(context, uri, systemDir) ?: return false
        return hasAnyBios(systemDir)
    }

    fun import(context: Context, uri: Uri, systemDir: String): File? {
        val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        val displayName = DocumentPathResolver.getDisplayName(context, uri.toString())
        val targetName = supportedBiosTargetName(displayName, size)
            ?: when (size) {
                BOOT_ROM_SIZE -> bootFileNames.first()
                FLASH_ROM_SIZE -> FLASH_FILE_NAME
                else -> null
            }
            ?: return null
        val directory = directory(systemDir).apply { mkdirs() }
        val target = File(directory, targetName)
        // A console ROM recognized only by its size must not replace a BIOS the
        // user already installed under the canonical name.
        if (isConsoleRomName(targetName) && !isExplicitBiosName(displayName) && target.isFile) return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (targetName in bootFileNames && looksLikeArcadeBootRom(target)) {
                // Naomi and Atomiswave boot ROMs are also 2 MiB, so a size-only
                // guess can install arcade firmware as the Dreamcast BIOS.
                target.delete()
                CrashLogger.logError(
                    TAG,
                    "Ignoring $displayName: Naomi/Atomiswave firmware, not a Dreamcast boot ROM"
                )
                return null
            }
            target.takeIf { it.isFile && it.length() == size }
        }.getOrNull()
    }

    /**
     * Copies every supported BIOS file from a user-picked folder into
     * `<system>/dc/`. Unrelated files in the folder are ignored. Returns true
     * when at least one BIOS file was installed.
     */
    fun installFromDirectory(context: Context, treeUri: Uri, systemDir: String): Boolean {
        val root = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull() ?: return false
        val targetDir = directory(systemDir).apply { mkdirs() }
        return copySupportedBiosFiles(context, root, targetDir, ProbeBudget())
    }

    private fun copySupportedBiosFiles(
        context: Context,
        source: DocumentFile,
        targetDir: File,
        budget: ProbeBudget
    ): Boolean {
        if (!budget.tryEnterDirectory()) return false
        var installed = false
        for (child in runCatching { source.listFiles() }.getOrDefault(emptyArray())) {
            val mimeType = runCatching { child.type }.getOrNull()
            if (child.isDirectory || mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                if (copySupportedBiosFiles(context, child, targetDir, budget)) installed = true
                continue
            }
            if (!budget.tryCheckFile()) continue
            val displayName = runCatching { child.name }.getOrNull().orEmpty().ifBlank {
                DocumentPathResolver.getDisplayName(context, child.uri.toString())
            }
            val size = runCatching { child.length() }.getOrDefault(-1L)
            val targetName = supportedBiosTargetName(displayName, size) ?: continue
            val target = File(targetDir, targetName)
            // A console ROM recognized only by its size must not replace a BIOS
            // the user already installed under the canonical name.
            if (isConsoleRomName(targetName) && !isExplicitBiosName(displayName) && target.isFile) continue
            val expectedSize = expectedBiosSize(targetName)
            if (copyToFile(context, child.uri, target, size, expectedSize)) {
                if (targetName in bootFileNames && looksLikeArcadeBootRom(target)) {
                    target.delete()
                    CrashLogger.logError(
                        TAG,
                        "Ignoring $displayName: Naomi/Atomiswave firmware, not a Dreamcast boot ROM"
                    )
                    continue
                }
                installed = true
            }
        }
        return installed
    }

    private fun expectedBiosSize(targetName: String): Long? = when (targetName) {
        in bootFileNames -> BOOT_ROM_SIZE
        in flashFileNames -> FLASH_ROM_SIZE
        else -> null
    }

    private fun copyToFile(
        context: Context,
        uri: Uri,
        target: File,
        sourceSize: Long,
        expectedSize: Long?
    ): Boolean {
        if (sourceSize > MAX_BIOS_BYTES) return false
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            val length = target.length()
            length > 0L && when {
                expectedSize != null -> length == expectedSize
                sourceSize > 0L -> length == sourceSize
                else -> true
            }
        }.getOrElse {
            target.delete()
            false
        }
    }

    /**
     * Name a supported BIOS file should be installed under, or null when the
     * file is not a known BIOS (name and size are both checked so unrelated
     * files with a matching extension are skipped).
     */
    internal fun supportedBiosTargetName(name: String?, size: Long): String? {
        val fileName = name?.lowercase()?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')
            ?: return null
        if (fileName.isBlank()) return null
        // Sega CD and 32X firmware is imported by BiosImport into jgenesis/system.
        if (SegaCdBios.regionForFileName(fileName) != null ||
            Sega32XBios.partForFileName(fileName) != null
        ) return null
        val extension = fileName.substringAfterLast('.', "")
        // Providers that cannot report a size give 0 or -1; the copied file is
        // validated against the expected size afterwards.
        val unknownSize = size <= 0L
        return when {
            fileName in bootFileNames ->
                fileName.takeIf { size == BOOT_ROM_SIZE || unknownSize }
            // Both flash names land under the name the libretro core opens.
            fileName in flashFileNames ->
                FLASH_FILE_NAME.takeIf { size == FLASH_ROM_SIZE || unknownSize }
            extension in arcadeArchiveExtensions &&
                fileName.substringBeforeLast('.') in arcadeBiosNames -> fileName
            // Dumps carry every kind of file name, so anything with the exact
            // size of a console ROM is accepted like the single-file import.
            size == BOOT_ROM_SIZE -> bootFileNames.first()
            size == FLASH_ROM_SIZE -> FLASH_FILE_NAME
            else -> null
        }
    }

    private class ProbeBudget {
        private var checkedFiles = 0
        private var checkedDirectories = 0

        fun tryCheckFile(): Boolean {
            if (checkedFiles >= MAX_PROBE_FILES) return false
            checkedFiles++
            return true
        }

        fun tryEnterDirectory(): Boolean {
            if (checkedDirectories >= MAX_PROBE_DIRECTORIES) return false
            checkedDirectories++
            return true
        }
    }
}
