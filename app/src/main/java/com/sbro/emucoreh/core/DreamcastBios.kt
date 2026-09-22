// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Dreamcast and arcade BIOS files. Flycast reads them from `<system>/dc/`:
 * `dc_boot.bin` (2 MiB) or `dc_bios.bin`, plus the 128 KiB `dc_flash.bin`.
 * Arcade titles load their BIOS from the MAME sets (`naomi.zip`,
 * `naomi2.zip`, `awbios.zip` and the game-specific sets).
 * Games boot without a console dump through the core's HLE BIOS, which the
 * frontend enables automatically when no dump is installed.
 */
object DreamcastBios {
    const val BOOT_ROM_SIZE = 2L * 1024L * 1024L
    const val FLASH_ROM_SIZE = 128L * 1024L
    val bootFileNames = listOf("dc_boot.bin", "dc_bios.bin")
    const val FLASH_FILE_NAME = "dc_flash.bin"

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
    private const val MAX_BIOS_BYTES = 64L * 1024L * 1024L
    private const val MAX_PROBE_DIRECTORIES = 64
    private const val MAX_PROBE_FILES = 256

    fun directory(systemDir: String): File = File(systemDir, "dc")

    fun findBootRom(systemDir: String): File? = bootFileNames
        .map { File(directory(systemDir), it) }
        .firstOrNull { it.isFile && it.length() == BOOT_ROM_SIZE }

    fun findFlashRom(systemDir: String): File? =
        File(directory(systemDir), FLASH_FILE_NAME).takeIf { it.isFile && it.length() == FLASH_ROM_SIZE }

    fun hasBootRom(systemDir: String): Boolean = findBootRom(systemDir) != null

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
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
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
            val expectedSize = expectedBiosSize(targetName)
            if (copyToFile(context, child.uri, File(targetDir, targetName), size, expectedSize)) {
                installed = true
            }
        }
        return installed
    }

    private fun expectedBiosSize(targetName: String): Long? = when (targetName) {
        in bootFileNames -> BOOT_ROM_SIZE
        FLASH_FILE_NAME -> FLASH_ROM_SIZE
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
        val extension = fileName.substringAfterLast('.', "")
        // Providers that cannot report a size give 0 or -1; the copied file is
        // validated against the expected size afterwards.
        val unknownSize = size <= 0L
        return when {
            fileName in bootFileNames ->
                fileName.takeIf { size == BOOT_ROM_SIZE || unknownSize }
            fileName == FLASH_FILE_NAME ->
                fileName.takeIf { size == FLASH_ROM_SIZE || unknownSize }
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
