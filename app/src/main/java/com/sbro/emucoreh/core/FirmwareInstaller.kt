package com.sbro.emucoreh.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Stages and validates a complete selection before publishing canonical firmware files. */
internal object FirmwareInstaller {
    data class Installed(val family: FirmwareContent.Family, val file: File)

    fun install(context: Context, uri: Uri, flycastSystem: String, segaSystem: File,
                families: Set<FirmwareContent.Family> = FirmwareContent.Family.entries.toSet()): List<Installed> = runCatching {
        val detected = context.contentResolver.openInputStream(uri)?.use(FirmwareContent::readSelection)
            .orEmpty().filter { it.family in families }
        val installed = mutableListOf<Installed>()
        for ((archive, roms) in detected.filter { it.family == FirmwareContent.Family.ARCADE }.groupBy { requireNotNull(it.archive) }) {
            val target = File(DreamcastBios.directory(flycastSystem), "$archive.zip")
            val previous = if (target.isFile) runCatching {
                target.inputStream().use(FirmwareContent::readSelection).filter { it.archive == archive }
            }.getOrDefault(emptyList()) else emptyList()
            val merged = (previous + roms).associateBy { it.name }.values.sortedBy { it.name }
            if (merged.none { it.bytes.size >= 65536 }) continue
            atomicWrite(target) { file ->
                ZipOutputStream(file.outputStream()).use { zip ->
                    for (rom in merged) {
                        zip.putNextEntry(ZipEntry(rom.name))
                        zip.write(rom.bytes)
                        zip.closeEntry()
                    }
                }
            }
            installed += Installed(FirmwareContent.Family.ARCADE, target)
        }
        for ((_, candidates) in detected.filter { it.family != FirmwareContent.Family.ARCADE }.groupBy { it.family to it.name }) {
            // A BIOS collection can contain several revisions for one slot. A stable
            // content order prevents provider enumeration order from choosing a version.
            val rom = candidates.minBy { candidate ->
                MessageDigest.getInstance("MD5").digest(candidate.bytes).joinToString("") { "%02x".format(it) }
            }
            val directory = when (rom.family) {
                FirmwareContent.Family.DREAMCAST -> DreamcastBios.directory(flycastSystem)
                FirmwareContent.Family.SEGA_CD, FirmwareContent.Family.SEGA_32X -> segaSystem
                FirmwareContent.Family.SATURN -> SaturnBios.directory(context)
                else -> error("Unexpected archive firmware")
            }
            val target = File(directory, rom.name)
            atomicWrite(target) { it.writeBytes(rom.bytes) }
            installed += Installed(rom.family, target)
        }
        installed
    }.getOrDefault(emptyList())

    private fun atomicWrite(target: File, write: (File) -> Unit) {
        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
        val temporary = File.createTempFile("firmware-", ".tmp", target.parentFile)
        try {
            write(temporary)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
