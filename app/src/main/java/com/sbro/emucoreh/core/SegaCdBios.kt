package com.sbro.emucoreh.core

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** User-supplied Sega CD firmware, isolated from Flycast and backup roots. */
object SegaCdBios {
    const val SIZE = 128 * 1024

    enum class Region(val fileName: String, internal val matchedNames: List<String>) {
        US(
            "bios_CD_U.bin",
            listOf("bios_cd_u.bin", "bios_cd_us.bin", "scd_us.bin", "bios_us.bin")
        ),
        EU(
            "bios_CD_E.bin",
            listOf("bios_cd_e.bin", "bios_cd_eu.bin", "scd_eu.bin", "bios_eu.bin")
        ),
        JP(
            "bios_CD_J.bin",
            listOf("bios_cd_j.bin", "bios_cd_jp.bin", "scd_jp.bin", "bios_jp.bin")
        )
    }

    fun directory(context: Context) = File(context.filesDir, "jgenesis/system")

    fun regionForFileName(name: String?): Region? {
        val fileName = name?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.lowercase() ?: return null
        return Region.entries.firstOrNull { fileName in it.matchedNames }
    }

    /** Identify a console boot ROM by its header, never by a translated dump filename. */
    internal fun detectRegion(bytes: ByteArray): Region? {
        if (bytes.size != SIZE) return null
        val system = bytes.copyOfRange(0x100, 0x110).toString(Charsets.US_ASCII)
        if (!system.startsWith("SEGA")) return null
        val title = bytes.copyOfRange(0x120, 0x180).toString(Charsets.US_ASCII)
        if (!Regex("CD[12]?\\s+BOOT\\s+ROM").containsMatchIn(title)) return null
        val regions = bytes.copyOfRange(0x1f0, 0x200).toString(Charsets.US_ASCII)
            .filter { it in "UEJ" }.toSet()
        return when (regions.singleOrNull()) {
            'U' -> Region.US
            'E' -> Region.EU
            'J' -> Region.JP
            else -> null
        }
    }

    internal fun normalizeDetected(bytes: ByteArray): ByteArray? {
        if (detectRegion(bytes) != null) return bytes
        if (bytes.size != SIZE) return null
        val swapped = bytes.copyOf()
        for (i in swapped.indices step 2) {
            val first = swapped[i]
            swapped[i] = swapped[i + 1]
            swapped[i + 1] = first
        }
        return swapped.takeIf { detectRegion(it) != null }
    }

    internal fun installDetectedTo(context: Context, uri: Uri, size: Long, directory: File): Region? = runCatching {
        if (size >= 0 && size != SIZE.toLong()) return null
        val raw = context.contentResolver.openInputStream(uri)?.use(::readValidated) ?: return null
        val bytes = normalizeDetected(raw) ?: return null
        val region = detectRegion(bytes) ?: return null
        writeFirmware(bytes, region, directory)
        region
    }.getOrNull()

    fun isInstalled(context: Context, region: Region): Boolean =
        directory(context).listFiles().orEmpty().any {
            it.name.lowercase() in region.matchedNames && it.isFile && it.length() == SIZE.toLong()
        }

    fun install(context: Context, uri: Uri, region: Region): Boolean =
        installTo(context, uri, region, directory(context))

    internal fun installTo(context: Context, uri: Uri, region: Region, directory: File): Boolean = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            readValidated(input)
        } ?: return false
        writeFirmware(bytes, region, directory)
        true
    }.getOrDefault(false)

    private fun writeFirmware(bytes: ByteArray, region: Region, directory: File) {
        check(directory.mkdirs() || directory.isDirectory)
        val temporary = File.createTempFile("${region.fileName}.", ".tmp", directory)
        try {
            temporary.outputStream().use { it.write(bytes) }
            Files.move(
                temporary.toPath(),
                File(directory, region.fileName).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } finally {
            temporary.delete()
        }
    }

    internal fun readValidated(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream(SIZE + 1)
        val buffer = ByteArray(8192)
        while (output.size() <= SIZE) {
            val read = input.read(buffer, 0, minOf(buffer.size, SIZE + 1 - output.size()))
            if (read < 0) break
            if (read == 0) {
                val next = input.read()
                if (next < 0) break
                output.write(next)
                continue
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray().takeIf { it.size == SIZE }
    }
}
