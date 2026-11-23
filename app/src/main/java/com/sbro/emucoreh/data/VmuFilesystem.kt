package com.sbro.emucoreh.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Calendar
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater

data class VmuSaveItem(
    val fileName: String,
    val shortDesc: String,
    val longDesc: String,
    val blocks: Int,
    val firstBlock: Int,
    val timestamp: String,
    val isGame: Boolean,
    val dirBlock: Int,
    val dirEntryOffset: Int,
    val iconPath: String?,
)

data class VmuCardSummary(
    val portName: String,
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val isFormatted: Boolean,
    val freeBlocks: Int,
    val totalBlocks: Int,
    val files: List<VmuSaveItem>,
)

/**
 * Pure-Kotlin 128 KiB Dreamcast Visual Memory Unit (VMU) FAT reader, formatter,
 * file remover, and 32x32 ARGB4444 icon PNG extractor.
 */
object VmuFilesystem {
    const val VMU_SIZE_BYTES = 256 * 512
    private const val BLOCK_SIZE = 512
    private const val TOTAL_BLOCKS = 256
    private const val USER_BLOCKS = 200
    private const val DEFAULT_ROOT_BLOCK = 255
    private const val DEFAULT_FAT_BLOCK = 254
    private const val DEFAULT_DIR_START = 253
    private const val DEFAULT_DIR_BLOCKS = 13

    private const val FAT_UNALLOCATED = 0xFFFC
    private const val FAT_EOF = 0xFFFA
    private const val FILE_TYPE_DATA = 0x33
    private const val FILE_TYPE_GAME = 0xCC

    private val portRegex = Regex("^vmu_save_([A-D][12])\\.bin$", RegexOption.IGNORE_CASE)
    private val shiftJis: Charset = runCatching { Charset.forName("Shift_JIS") }.getOrDefault(Charsets.ISO_8859_1)

    fun portLabelForFileName(fileName: String): String {
        val m = portRegex.find(fileName)
        return if (m != null) "VMU ${m.groupValues[1].uppercase(Locale.US)}" else fileName.substringBeforeLast('.')
    }

    /**
     * Guards shared `<system>/dc` files so Dreamcast BIOS blobs (`dc_boot.bin`, `dc_flash.bin`)
     * are never treated or formatted as VMU cards.
     */
    fun isVmuImageFile(file: File): Boolean {
        if (!file.isFile) return false
        val lower = file.name.lowercase(Locale.ROOT)
        if (!lower.endsWith(".bin")) return false
        if (lower == "dc_boot.bin" || lower == "dc_flash.bin") return false
        if (lower.startsWith("dc_") || lower.contains("bios")) return false
        return portRegex.matches(file.name) || file.length() == VMU_SIZE_BYTES.toLong()
    }

    fun inspectCard(file: File, iconCacheDir: File?): VmuCardSummary {
        val portName = portLabelForFileName(file.name)
        val sizeBytes = file.length()
        val modifiedAt = file.lastModified()
        if (!isVmuImageFile(file) || sizeBytes != VMU_SIZE_BYTES.toLong()) {
            return VmuCardSummary(
                portName = portName,
                fileName = file.name,
                filePath = file.absolutePath,
                sizeBytes = sizeBytes,
                modifiedAt = modifiedAt,
                isFormatted = false,
                freeBlocks = USER_BLOCKS,
                totalBlocks = USER_BLOCKS,
                files = emptyList(),
            )
        }

        val data = runCatching { file.readBytes() }.getOrNull()
        if (data == null || data.size != VMU_SIZE_BYTES) {
            return VmuCardSummary(
                portName = portName,
                fileName = file.name,
                filePath = file.absolutePath,
                sizeBytes = sizeBytes,
                modifiedAt = modifiedAt,
                isFormatted = false,
                freeBlocks = USER_BLOCKS,
                totalBlocks = USER_BLOCKS,
                files = emptyList(),
            )
        }

        val rootOff = DEFAULT_ROOT_BLOCK * BLOCK_SIZE
        val isFormatted = (0 until 16).all { (data[rootOff + it].toInt() and 0xFF) == 0x55 }
        if (!isFormatted) {
            return VmuCardSummary(
                portName = portName,
                fileName = file.name,
                filePath = file.absolutePath,
                sizeBytes = sizeBytes,
                modifiedAt = modifiedAt,
                isFormatted = false,
                freeBlocks = USER_BLOCKS,
                totalBlocks = USER_BLOCKS,
                files = emptyList(),
            )
        }

        val fatBlock = readU16LE(data, rootOff + 0x46).takeIf { it in 200..255 } ?: DEFAULT_FAT_BLOCK
        val dirStart = readU16LE(data, rootOff + 0x4A).takeIf { it in 200..254 } ?: DEFAULT_DIR_START
        val dirLen = readU16LE(data, rootOff + 0x4C).takeIf { it in 1..20 } ?: DEFAULT_DIR_BLOCKS
        val userBlocks = readU16LE(data, rootOff + 0x50).takeIf { it in 1..USER_BLOCKS } ?: USER_BLOCKS

        val fatOff = fatBlock * BLOCK_SIZE
        var freeBlocks = 0
        for (b in 0 until userBlocks) {
            if (readU16LE(data, fatOff + b * 2) == FAT_UNALLOCATED) {
                freeBlocks++
            }
        }

        val items = mutableListOf<VmuSaveItem>()
        for (dIdx in 0 until dirLen) {
            val dirBlock = dirStart - dIdx
            if (dirBlock !in 0 until TOTAL_BLOCKS) break
            val blockOff = dirBlock * BLOCK_SIZE
            for (slot in 0 until 16) {
                val entOff = blockOff + slot * 32
                val fileType = data[entOff].toInt() and 0xFF
                if (fileType != FILE_TYPE_DATA && fileType != FILE_TYPE_GAME) continue

                val firstBlock = readU16LE(data, entOff + 0x02)
                val fileName = decodeAsciiTrim(data, entOff + 0x04, 12)
                if (fileName.isEmpty() || firstBlock >= userBlocks) continue

                val blocksCount = readU16LE(data, entOff + 0x18).coerceAtLeast(1)
                val headerOffsetBlocks = readU16LE(data, entOff + 0x1A)
                val timestamp = formatBcdTimestamp(data, entOff + 0x10)
                val isGame = (fileType == FILE_TYPE_GAME)

                val vmsBytes = readChainBytes(data, fatOff, firstBlock, headerOffsetBlocks + 2)
                val headerStart = headerOffsetBlocks * BLOCK_SIZE
                val shortDesc = if (vmsBytes.size >= headerStart + 16) {
                    decodeTextTrim(vmsBytes, headerStart, 16)
                } else ""
                val longDesc = if (vmsBytes.size >= headerStart + 48) {
                    decodeTextTrim(vmsBytes, headerStart + 16, 32)
                } else ""

                val iconPath = if (iconCacheDir != null && vmsBytes.size >= headerStart + 0x280) {
                    ensureIconPng(
                        vmsBytes = vmsBytes,
                        headerStart = headerStart,
                        iconCacheDir = iconCacheDir,
                        cacheKey = "${file.nameWithoutExtension}_${fileName}_$modifiedAt",
                    )
                } else {
                    null
                }

                items += VmuSaveItem(
                    fileName = fileName,
                    shortDesc = shortDesc.ifBlank { longDesc }.ifBlank { fileName },
                    longDesc = longDesc,
                    blocks = blocksCount,
                    firstBlock = firstBlock,
                    timestamp = timestamp,
                    isGame = isGame,
                    dirBlock = dirBlock,
                    dirEntryOffset = slot * 32,
                    iconPath = iconPath,
                )
            }
        }

        return VmuCardSummary(
            portName = portName,
            fileName = file.name,
            filePath = file.absolutePath,
            sizeBytes = sizeBytes,
            modifiedAt = modifiedAt,
            isFormatted = true,
            freeBlocks = freeBlocks,
            totalBlocks = userBlocks,
            files = items,
        )
    }

    fun deleteFile(file: File, item: VmuSaveItem): Boolean = runCatching {
        if (!isVmuImageFile(file) || file.length() != VMU_SIZE_BYTES.toLong()) return false
        val data = file.readBytes()
        if (data.size != VMU_SIZE_BYTES) return false

        val rootOff = DEFAULT_ROOT_BLOCK * BLOCK_SIZE
        val isFormatted = (0 until 16).all { (data[rootOff + it].toInt() and 0xFF) == 0x55 }
        if (!isFormatted) return false

        val fatBlock = readU16LE(data, rootOff + 0x46).takeIf { it in 200..255 } ?: DEFAULT_FAT_BLOCK
        val fatOff = fatBlock * BLOCK_SIZE

        // Free FAT chain safely with cycle guard
        val visited = HashSet<Int>()
        var cur = item.firstBlock
        var steps = 0
        while (cur in 0 until USER_BLOCKS && steps < USER_BLOCKS && visited.add(cur)) {
            val next = readU16LE(data, fatOff + cur * 2)
            writeU16LE(data, fatOff + cur * 2, FAT_UNALLOCATED)
            if (next == FAT_EOF || next == FAT_UNALLOCATED) break
            cur = next
            steps++
        }

        // Clear the 32-byte directory entry
        val entAbs = item.dirBlock * BLOCK_SIZE + item.dirEntryOffset
        if (entAbs in 0..(VMU_SIZE_BYTES - 32)) {
            for (i in 0 until 32) {
                data[entAbs + i] = 0
            }
        }

        atomicWrite(file, data)
        true
    }.getOrDefault(false)

    fun formatCard(file: File, nowMillis: Long = System.currentTimeMillis()): Boolean = runCatching {
        if (!isVmuImageFile(file)) return false
        val data = ByteArray(VMU_SIZE_BYTES)

        // Root block (255)
        val rootOff = DEFAULT_ROOT_BLOCK * BLOCK_SIZE
        for (i in 0 until 16) {
            data[rootOff + i] = 0x55
        }
        writeBcdTimestamp(data, rootOff + 0x30, nowMillis)
        writeU16LE(data, rootOff + 0x44, DEFAULT_ROOT_BLOCK)
        writeU16LE(data, rootOff + 0x46, DEFAULT_FAT_BLOCK)
        writeU16LE(data, rootOff + 0x48, 1)
        writeU16LE(data, rootOff + 0x4A, DEFAULT_DIR_START)
        writeU16LE(data, rootOff + 0x4C, DEFAULT_DIR_BLOCKS)
        writeU16LE(data, rootOff + 0x4E, 0)
        writeU16LE(data, rootOff + 0x50, USER_BLOCKS)

        // FAT block (254)
        val fatOff = DEFAULT_FAT_BLOCK * BLOCK_SIZE
        for (b in 0 until USER_BLOCKS) {
            writeU16LE(data, fatOff + b * 2, FAT_UNALLOCATED)
        }
        for (b in USER_BLOCKS until (DEFAULT_DIR_START - DEFAULT_DIR_BLOCKS + 1)) {
            writeU16LE(data, fatOff + b * 2, FAT_EOF)
        }
        for (b in (DEFAULT_DIR_START - DEFAULT_DIR_BLOCKS + 2)..DEFAULT_DIR_START) {
            writeU16LE(data, fatOff + b * 2, b - 1)
        }
        writeU16LE(data, fatOff + (DEFAULT_DIR_START - DEFAULT_DIR_BLOCKS + 1) * 2, FAT_EOF)
        writeU16LE(data, fatOff + DEFAULT_FAT_BLOCK * 2, FAT_EOF)
        writeU16LE(data, fatOff + DEFAULT_ROOT_BLOCK * 2, FAT_EOF)

        atomicWrite(file, data)
        true
    }.getOrDefault(false)

    /** Decodes a 32x32 4-bpp Dreamcast VMU icon from [vmsBytes] starting at [headerStart]. */
    internal fun decodeIconRgba(vmsBytes: ByteArray, headerStart: Int): ByteArray? {
        if (vmsBytes.size < headerStart + 0x280) return null
        val pal = ByteArray(16 * 4)
        for (i in 0 until 16) {
            val v = readU16LE(vmsBytes, headerStart + 0x60 + i * 2)
            val b = (v and 0x0F) * 17
            val g = ((v ushr 4) and 0x0F) * 17
            val r = ((v ushr 8) and 0x0F) * 17
            val a = ((v ushr 12) and 0x0F) * 17
            pal[i * 4] = r.toByte()
            pal[i * 4 + 1] = g.toByte()
            pal[i * 4 + 2] = b.toByte()
            pal[i * 4 + 3] = a.toByte()
        }
        val rgba = ByteArray(32 * 32 * 4)
        val pixBase = headerStart + 0x80
        var out = 0
        for (i in 0 until 512) {
            val byteVal = vmsBytes[pixBase + i].toInt() and 0xFF
            val hi = (byteVal ushr 4) and 0x0F
            val lo = byteVal and 0x0F
            rgba[out++] = pal[hi * 4]
            rgba[out++] = pal[hi * 4 + 1]
            rgba[out++] = pal[hi * 4 + 2]
            rgba[out++] = pal[hi * 4 + 3]
            rgba[out++] = pal[lo * 4]
            rgba[out++] = pal[lo * 4 + 1]
            rgba[out++] = pal[lo * 4 + 2]
            rgba[out++] = pal[lo * 4 + 3]
        }
        return rgba
    }

    private fun ensureIconPng(
        vmsBytes: ByteArray,
        headerStart: Int,
        iconCacheDir: File,
        cacheKey: String,
    ): String? {
        val safeName = cacheKey.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png"
        val outFile = File(iconCacheDir, safeName)
        if (outFile.isFile && outFile.length() > 32L) {
            return outFile.absolutePath
        }
        val rgba = decodeIconRgba(vmsBytes, headerStart) ?: return null
        val pngBytes = encodeRgbaPng32x32(rgba)
        return runCatching {
            iconCacheDir.mkdirs()
            atomicWrite(outFile, pngBytes)
            outFile.absolutePath
        }.getOrNull()
    }

    /** Minimal standard PNG encoder for 32x32 RGBA images (zero Android Bitmap dependency). */
    internal fun encodeRgbaPng32x32(rgba: ByteArray): ByteArray {
        val width = 32
        val height = 32
        val rawScanlines = ByteArray(height * (1 + width * 4))
        var src = 0
        var dst = 0
        for (y in 0 until height) {
            rawScanlines[dst++] = 0 // Filter type None
            System.arraycopy(rgba, src, rawScanlines, dst, width * 4)
            src += width * 4
            dst += width * 4
        }

        val compressed = ByteArrayOutputStream(1024)
        val deflater = Deflater()
        try {
            deflater.setInput(rawScanlines)
            deflater.finish()
            val buf = ByteArray(512)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n > 0) compressed.write(buf, 0, n)
            }
        } finally {
            deflater.end()
        }

        val out = ByteArrayOutputStream(2048)
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        val ihdr = ByteArray(13)
        putIntBE(ihdr, 0, width)
        putIntBE(ihdr, 4, height)
        ihdr[8] = 8 // bit depth
        ihdr[9] = 6 // RGBA
        ihdr[10] = 0
        ihdr[11] = 0
        ihdr[12] = 0
        writePngChunk(out, "IHDR", ihdr)
        writePngChunk(out, "IDAT", compressed.toByteArray())
        writePngChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writePngChunk(out: ByteArrayOutputStream, type: String, payload: ByteArray) {
        val lenBytes = ByteArray(4)
        putIntBE(lenBytes, 0, payload.size)
        out.write(lenBytes)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(payload)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(payload)
        val crcBytes = ByteArray(4)
        putIntBE(crcBytes, 0, crc.value.toInt())
        out.write(crcBytes)
    }

    private fun readChainBytes(data: ByteArray, fatOff: Int, startBlock: Int, maxBlocks: Int): ByteArray {
        val out = ByteArrayOutputStream(maxBlocks * BLOCK_SIZE)
        val visited = HashSet<Int>()
        var cur = startBlock
        var count = 0
        while (cur in 0 until USER_BLOCKS && count < maxBlocks && visited.add(cur)) {
            out.write(data, cur * BLOCK_SIZE, BLOCK_SIZE)
            val next = readU16LE(data, fatOff + cur * 2)
            if (next == FAT_EOF || next == FAT_UNALLOCATED) break
            cur = next
            count++
        }
        return out.toByteArray()
    }

    private fun readU16LE(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 1 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun writeU16LE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putIntBE(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = ((v ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((v ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (v and 0xFF).toByte()
    }

    private fun decodeAsciiTrim(data: ByteArray, offset: Int, maxLen: Int): String =
        String(data, offset, maxLen, Charsets.US_ASCII)
            .trim { it == '\u0000' || it == ' ' }

    private fun decodeTextTrim(data: ByteArray, offset: Int, maxLen: Int): String {
        val slice = ByteArray(maxLen)
        System.arraycopy(data, offset, slice, 0, maxLen)
        val zeroIdx = slice.indexOf(0.toByte())
        val end = if (zeroIdx >= 0) zeroIdx else maxLen
        return String(slice, 0, end, shiftJis)
            .replace('\u0000', ' ')
            .trim()
    }

    private fun fromBcd(b: Byte): Int {
        val v = b.toInt() and 0xFF
        val hi = (v ushr 4) and 0x0F
        val lo = v and 0x0F
        return if (hi <= 9 && lo <= 9) hi * 10 + lo else 0
    }

    private fun toBcd(v: Int): Byte {
        val c = v.coerceIn(0, 99)
        return (((c / 10) shl 4) or (c % 10)).toByte()
    }

    private fun formatBcdTimestamp(data: ByteArray, offset: Int): String {
        val century = fromBcd(data[offset])
        val year = fromBcd(data[offset + 1])
        val month = fromBcd(data[offset + 2])
        val day = fromBcd(data[offset + 3])
        val hour = fromBcd(data[offset + 4])
        val min = fromBcd(data[offset + 5])
        val fullYear = century * 100 + year
        if (fullYear < 1990 || month !in 1..12 || day !in 1..31) return ""
        return String.format(Locale.US, "%02d/%02d/%04d %02d:%02d", day, month, fullYear, hour, min)
    }

    private fun writeBcdTimestamp(data: ByteArray, offset: Int, millis: Long) {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val fullYear = cal.get(Calendar.YEAR)
        data[offset] = toBcd(fullYear / 100)
        data[offset + 1] = toBcd(fullYear % 100)
        data[offset + 2] = toBcd(cal.get(Calendar.MONTH) + 1)
        data[offset + 3] = toBcd(cal.get(Calendar.DAY_OF_MONTH))
        data[offset + 4] = toBcd(cal.get(Calendar.HOUR_OF_DAY))
        data[offset + 5] = toBcd(cal.get(Calendar.MINUTE))
        data[offset + 6] = toBcd(cal.get(Calendar.SECOND))
        val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Mon=0..Sun=6
        data[offset + 7] = toBcd(dow)
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw IllegalStateException("Missing parent dir")
        if (!parent.isDirectory) parent.mkdirs()
        val tmp = File.createTempFile("vmu-", ".tmp", parent)
        try {
            tmp.writeBytes(bytes)
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } finally {
            tmp.delete()
        }
    }
}
