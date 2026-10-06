// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

/**
 * Heuristics that keep BIOS dumps out of the game library.
 *
 * Dreamcast boot/flash ROMs and arcade BIOS blobs are not games, so the
 * library scanners filter them out instead of listing them as content.
 */
object BiosValidator {

    private val biosImageExtensions = setOf("bin", "rom")
    private val fileNameHints = listOf("dc_boot", "dc_bios", "bios", "flash")

    fun isLikelyBiosLibraryEntry(
        fileName: String,
        title: String?,
        serial: String?
    ): Boolean {
        val lowerTitle = title.orEmpty().lowercase()
        val lowerSerial = serial.orEmpty().lowercase()
        val titleLooksLikeBios = lowerTitle == "bios" ||
            lowerTitle.contains("dreamcast bios") ||
            lowerTitle.contains("bios dump")
        val serialLooksLikeBios = lowerSerial.contains("bios")
        return titleLooksLikeBios || serialLooksLikeBios || isLikelyBiosName(fileName)
    }

    fun isLikelyBiosName(name: String?): Boolean {
        val fileName = name?.lowercase() ?: return false
        val ext = fileName.substringAfterLast('.', "")
        return ext in biosImageExtensions && fileNameHints.any(fileName::contains)
    }
}
