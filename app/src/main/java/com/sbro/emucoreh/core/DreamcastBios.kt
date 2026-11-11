// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import java.io.File
import java.util.zip.CRC32
import java.util.Locale

/**
 * Dreamcast and arcade BIOS files. Flycast reads them from `<system>/dc/`:
 * `dc_boot.bin` (2 MiB) or `dc_bios.bin`, plus the 128 KiB flash ROM, which the
 * core names `dc_nvmem.bin` (`dc_flash.bin` is the standalone Flycast name and
 * stays accepted on import). Arcade titles load their BIOS from the MAME sets
 * (`naomi.zip`, `naomi2.zip`, `awbios.zip` and the game-specific sets).
 * Dreamcast requires an imported console boot ROM before a session can start.
 */
object DreamcastBios {
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
    ) + ArcadeBiosFingerprints.roms.map { it.archive }

    private val arcadeArchiveExtensions = setOf("zip", "7z")
    private val arcadeBootRomMarkers = listOf("NAOMI", "ATOMISWAVE")
    private const val MAX_BOOT_ROM_SCAN_BYTES = 2 * 1024 * 1024

    fun directory(systemDir: String): File = File(systemDir, "dc")

    fun findBootRom(systemDir: String): File? = bootFileNames
        .map { File(directory(systemDir), it) }
        .firstOrNull { it.isFile && it.length() == BOOT_ROM_SIZE &&
            runCatching { FirmwareContent.isDreamcastBoot(it.readBytes()) }.getOrDefault(false) }

    fun findFlashRom(systemDir: String): File? = flashFileNames
        .map { File(directory(systemDir), it) }
        .firstOrNull { it.isFile && it.length() == FLASH_ROM_SIZE &&
            runCatching { FirmwareContent.isDreamcastFlash(it.readBytes()) }.getOrDefault(false) }

    /**
     * The core reads and writes the flash ROM as `dc_nvmem.bin`. Dumps and older
     * installs use the standalone name `dc_flash.bin`, so a legacy file is
     * copied once under the name the core actually opens.
     */
    fun migrateLegacyFlash(systemDir: String): Boolean {
        val dir = directory(systemDir)
        val canonical = File(dir, FLASH_FILE_NAME)
        if (findFlashRom(systemDir)?.name == FLASH_FILE_NAME) return false
        val legacy = File(dir, LEGACY_FLASH_FILE_NAME)
        if (!legacy.isFile || legacy.length() != FLASH_ROM_SIZE ||
            !runCatching { FirmwareContent.isDreamcastFlash(legacy.readBytes()) }.getOrDefault(false)) return false
        return runCatching {
            legacy.copyTo(canonical, overwrite = true)
            canonical.isFile
        }.getOrDefault(false)
    }

    fun hasBootRom(systemDir: String): Boolean = findBootRom(systemDir) != null

    fun hasUsableBootRom(systemDir: String): Boolean =
        findBootRom(systemDir) != null

    /** Flycast also handles arcade archives, which use their own firmware sets. */
    fun requiresConsoleBios(biosOnly: Boolean, contentName: String): Boolean =
        biosOnly || contentName.substringAfterLast('.', "").lowercase(Locale.ROOT) !in
            (GameFormats.romExtensions + "bin")

    /**
     * Naomi and Atomiswave boot ROMs are 2 MiB as well, so a dump recognized
     * only by its size can end up installed as the Dreamcast BIOS. Those
     * firmwares carry their own product names, which a Dreamcast boot ROM never
     * contains; such a dump must not boot on Dreamcast hardware.
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

    /** True when at least one Naomi, Naomi 2 or Atomiswave BIOS set is installed. */
    fun hasArcadeBios(systemDir: String): Boolean = arcadeBiosNames.any { name ->
        arcadeArchiveExtensions.any { extension ->
            val file = File(directory(systemDir), "$name.$extension")
            file.isFile && file.length() <= FirmwareContent.MAX_INPUT && runCatching {
                file.inputStream().use(FirmwareContent::readSelection).any {
                    it.family == FirmwareContent.Family.ARCADE && it.archive == name && it.bytes.size >= 65536
                }
            }.getOrDefault(false)
        }
    }

    /** True when any supported console or arcade BIOS is installed. */
    fun hasAnyBios(systemDir: String): Boolean =
        hasBootRom(systemDir) || findFlashRom(systemDir) != null || hasArcadeBios(systemDir)

}
