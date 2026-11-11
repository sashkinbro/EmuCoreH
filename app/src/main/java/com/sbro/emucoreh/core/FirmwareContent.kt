package com.sbro.emucoreh.core

import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel

/** Firmware identity comes from bytes, never the user/provider filename. */
internal object FirmwareContent {
    const val MAX_INPUT = 64 * 1024 * 1024
    const val MAX_ROM = 2 * 1024 * 1024
    private const val MAX_ENTRIES = 256
    enum class Family { DREAMCAST, ARCADE, SEGA_CD, SEGA_32X, SATURN }
    data class Rom(val family: Family, val name: String, val bytes: ByteArray, val archive: String? = null)

    fun identify(bytes: ByteArray): List<Rom> {
        val crc = CRC32().apply { update(bytes) }.value
        val arcade = ArcadeBiosFingerprints.roms.filter { it.size == bytes.size && it.crc == crc }
        if (arcade.isNotEmpty()) return arcade.map { Rom(Family.ARCADE, it.name, bytes, it.archive) }
        if (SaturnBios.isValid(bytes)) return listOf(Rom(Family.SATURN, SaturnBios.FILE_NAME, bytes))
        Sega32XBios.Part.entries.firstOrNull { it.size == bytes.size && it.crc32 == crc }?.let {
            return listOf(Rom(Family.SEGA_32X, it.fileName, bytes))
        }
        SegaCdBios.normalizeDetected(bytes)?.let { normalized ->
            val region = requireNotNull(SegaCdBios.detectRegion(normalized))
            return listOf(Rom(Family.SEGA_CD, region.fileName, normalized))
        }
        if (isDreamcastBoot(bytes)) return listOf(Rom(Family.DREAMCAST, "dc_boot.bin", bytes))
        if (isDreamcastFlash(bytes)) return listOf(Rom(Family.DREAMCAST, DreamcastBios.FLASH_FILE_NAME, bytes))
        return emptyList()
    }

    fun isDreamcastBoot(bytes: ByteArray): Boolean {
        if (bytes.size != DreamcastBios.BOOT_ROM_SIZE.toInt()) return false
        // Official Flycast reference dump plus known console revisions. Header
        // recognition also permits legitimate regional/custom console firmware.
        val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        if (md5 in setOf("e10c53c2f8b90bab96ead2d368858623", "a5c6a00818f97c5e3e91569ee22416dc",
                "37c921eb47532cae8fb70e5d987ce91c", "eafca1eed2d7f76c487e940597c2a786")) return true
        val header = bytes.copyOfRange(0x7b0, 0x830).toString(Charsets.US_ASCII)
        return header.startsWith("SEGA SEGAKATANA  KABUTO Ver.") &&
            header.contains("Copyright(c) SEGA ENTERPRISES, LTD.") &&
            !DreamcastBios.looksLikeArcadeBootRom(bytes)
    }

    fun isDreamcastFlash(bytes: ByteArray): Boolean = bytes.size == DreamcastBios.FLASH_ROM_SIZE.toInt() &&
        listOf(0x1a005, 0x1a0a5).all { offset ->
            bytes.copyOfRange(offset, offset + 11).toString(Charsets.US_ASCII) == "Dreamcast  "
        }

    /** Bounds both compressed input and actual expanded bytes, including skipped entries. */
    fun readSelection(input: InputStream): List<Rom> {
        val buffered = BufferedInputStream(input)
        buffered.mark(6)
        val signature = ByteArray(6)
        var signatureSize = 0
        while (signatureSize < signature.size) {
            val value = buffered.read()
            if (value < 0) break
            signature[signatureSize++] = value.toByte()
        }
        buffered.reset()
        val sevenZMagic = byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c)
        val zipped = signatureSize >= 2 && signature[0] == 0x50.toByte() && signature[1] == 0x4b.toByte()
        val sevenZipped = signatureSize == 6 && signature.contentEquals(sevenZMagic)
        // Large unrelated games in a BIOS folder need at most a 2 MiB probe.
        val bytes = readBounded(buffered, if (zipped || sevenZipped) MAX_INPUT else MAX_ROM)
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
            val found = mutableListOf<Rom>()
            var remaining = MAX_INPUT
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entries = 0
                while (zip.nextEntry != null) {
                    check(++entries <= MAX_ENTRIES) { "Too many firmware entries" }
                    val expanded = readBounded(zip, minOf(MAX_ROM, remaining))
                    remaining -= expanded.size
                    found += identify(expanded)
                }
            }
            return found
        }
        if (bytes.size >= 6 && bytes.copyOfRange(0, 6).contentEquals(sevenZMagic)) {
            val found = mutableListOf<Rom>()
            var remaining = MAX_INPUT
            SevenZFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(bytes))
                .setMaxMemoryLimitKiB(64 * 1024).get().use { archive ->
                    var entries = 0
                    while (true) {
                        val entry = archive.nextEntry ?: break
                        check(++entries <= MAX_ENTRIES) { "Too many firmware entries" }
                        check(entry.size in 0..minOf(MAX_ROM, remaining).toLong()) { "Firmware entry too large" }
                        val expanded = ByteArray(entry.size.toInt())
                        var offset = 0
                        while (offset < expanded.size) {
                            val count = archive.read(expanded, offset, expanded.size - offset)
                            check(count > 0) { "Truncated firmware archive" }
                            offset += count
                        }
                        remaining -= expanded.size
                        found += identify(expanded)
                    }
                }
            return found
        }
        return identify(bytes)
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (count < 0) break
            if (count == 0) {
                val value = input.read()
                if (value < 0) break
                check(output.size() < limit) { "Firmware input too large" }
                output.write(value)
            } else {
                check(output.size() + count <= limit) { "Firmware input too large" }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }
}
