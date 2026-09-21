// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Dreamcast BIOS files. Flycast reads them from `<system>/dc/`:
 * `dc_boot.bin` (2 MiB) or `dc_bios.bin`, plus the 128 KiB `dc_flash.bin`.
 * Games boot without them through the core's HLE BIOS, which the frontend
 * enables automatically when no dump is installed.
 */
object DreamcastBios {
    const val BOOT_ROM_SIZE = 2L * 1024L * 1024L
    const val FLASH_ROM_SIZE = 128L * 1024L
    val bootFileNames = listOf("dc_boot.bin", "dc_bios.bin")
    const val FLASH_FILE_NAME = "dc_flash.bin"

    fun directory(systemDir: String): File = File(systemDir, "dc")

    fun findBootRom(systemDir: String): File? = bootFileNames
        .map { File(directory(systemDir), it) }
        .firstOrNull { it.isFile && it.length() == BOOT_ROM_SIZE }

    fun findFlashRom(systemDir: String): File? =
        File(directory(systemDir), FLASH_FILE_NAME).takeIf { it.isFile && it.length() == FLASH_ROM_SIZE }

    fun hasBootRom(systemDir: String): Boolean = findBootRom(systemDir) != null

    /**
     * Copies a user-picked BIOS dump into `<system>/dc/`. The destination name
     * is derived from the file size: 2 MiB is the boot ROM, 128 KiB the flash
     * ROM. Returns the installed file, or null when the size is unknown.
     */
    fun import(context: Context, uri: Uri, systemDir: String): File? {
        val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        val targetName = when (size) {
            BOOT_ROM_SIZE -> bootFileNames.first()
            FLASH_ROM_SIZE -> FLASH_FILE_NAME
            else -> return null
        }
        val directory = directory(systemDir).apply { mkdirs() }
        val target = File(directory, targetName)
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target.takeIf { it.isFile && it.length() == size }
        }.getOrNull()
    }
}
