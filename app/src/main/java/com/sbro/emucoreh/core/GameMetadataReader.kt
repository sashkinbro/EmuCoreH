package com.sbro.emucoreh.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Best-effort metadata reader for Dreamcast-family content.
 *
 * Dreamcast discs carry an IP.BIN boot sector with the game title and product
 * code. The sector is read straight from the image: for GDI the data track
 * listed in the descriptor is used, for CUE the INDEX 01 position of the first
 * track, and for plain ISO/BIN images the start of the file. Arcade ROM sets
 * and compressed images fall back to the filename-derived title handled by the
 * library layer.
 */
object GameMetadataReader {

    private const val IP_BIN_SIZE = 0x100
    private const val AREA_OFFSET = 0x30
    private const val AREA_LENGTH = 8
    private const val TITLE_OFFSET = 0x80
    private const val TITLE_LENGTH = 0x80
    private const val PRODUCT_OFFSET = 0x40
    private const val PRODUCT_LENGTH = 10

    fun read(context: Context, path: String): GameMetadata? {
        val bytes = if (path.startsWith("content://")) {
            readBootSectorFromUri(context, Uri.parse(path))
        } else {
            readBootSectorFromFile(File(path))
        } ?: return null
        return parseIpBin(bytes)
    }

    private fun readBootSectorFromFile(file: File): ByteArray? {
        if (!file.isFile) return null
        return when (file.extension.lowercase(Locale.ROOT)) {
            "gdi" -> readGdiBootSector(file)
            "cue" -> readCueBootSector(file)
            "iso", "bin" -> readAt(file, 0L, IP_BIN_SIZE)
            else -> null
        }
    }

    private fun readGdiBootSector(gdi: File): ByteArray? {
        val directory = gdi.parentFile ?: return null
        for (line in gdi.readLines()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 6) continue
            val trackType = parts[2].toIntOrNull() ?: continue
            // Type 4 is a data track; audio tracks cannot contain the boot sector.
            if (trackType != 4) continue
            val trackFile = File(directory, parts[4])
            if (!trackFile.isFile) continue
            val start = parts[5].toLongOrNull() ?: 0L
            val sectorSize = parts[3].toIntOrNull() ?: 2352
            readAt(trackFile, start + sectorDataOffset("MODE1", sectorSize), IP_BIN_SIZE)?.let { return it }
        }
        return null
    }

    private fun readCueBootSector(cue: File): ByteArray? {
        val directory = cue.parentFile ?: return null
        var currentFile: File? = null
        var currentType = ""
        var inDataTrack = false
        for (line in cue.readLines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("FILE", ignoreCase = true) -> {
                    val name = trimmed.substringAfter('"', "").substringBefore('"')
                    currentFile = File(directory, name.ifEmpty { trimmed.substringAfter("FILE ").substringBefore(' ') })
                }
                trimmed.startsWith("TRACK", ignoreCase = true) -> {
                    currentType = trimmed.split(Regex("\\s+")).getOrNull(2).orEmpty().uppercase(Locale.ROOT)
                    inDataTrack = currentType.startsWith("MODE1") || currentType.startsWith("MODE2")
                }
                inDataTrack && trimmed.startsWith("INDEX 01", ignoreCase = true) -> {
                    val file = currentFile ?: return null
                    val position = trimmed.substringAfter("INDEX 01").trim()
                    val sectors = parseMsf(position) ?: 0L
                    val declared = currentType.substringAfter('/', "2352").toIntOrNull() ?: 2352
                    // MODE1/2352 images store 2352 bytes per sector; MODE1/2048
                    // images store 2048. Use the file size to tell them apart.
                    val bytesPerSector = if (declared >= 2352 && file.length() % 2352L == 0L) 2352L else 2048L
                    val offset = sectorDataOffset(currentType, bytesPerSector.toInt())
                    return readAt(file, sectors * bytesPerSector + offset, IP_BIN_SIZE)
                }
            }
        }
        return null
    }

    private val cueFileDirective = Regex(
        """^\s*FILE\s+(?:"([^"]+)"|(\S+))\s+(\S+)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseMsf(value: String): Long? {
        val parts = value.split(':')
        if (parts.size != 3) return null
        val minutes = parts[0].toLongOrNull() ?: return null
        val seconds = parts[1].toLongOrNull() ?: return null
        val frames = parts[2].toLongOrNull() ?: return null
        return (minutes * 60 + seconds) * 75 + frames
    }

    private fun readAt(file: File, offset: Long, length: Int): ByteArray? = runCatching {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < offset + length) return null
            val bytes = ByteArray(length)
            input.seek(offset)
            input.readFully(bytes)
            bytes
        }
    }.getOrNull()

    private fun readBootSectorFromUri(context: Context, uri: Uri): ByteArray? = runCatching {
        val name = DocumentPathResolver.getDisplayName(context, uri.toString())
        when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "cue" -> {
                val text = readDocumentText(context, uri) ?: return null
                val track = cueDataTrack(text) ?: return null
                val document = DocumentPathResolver.resolveTrackDocument(context, uri, track.first) ?: return null
                readDocumentHead(context, document.uri, track.second)
            }
            "gdi" -> {
                val text = readDocumentText(context, uri) ?: return null
                val track = gdiDataTrack(text) ?: return null
                val document = DocumentPathResolver.resolveTrackDocument(context, uri, track.first) ?: return null
                readDocumentHead(context, document.uri, track.second)
            }
            "iso" -> readDocumentHead(context, uri, 0)
            else -> null
        }
    }.getOrNull()

    private fun readDocumentText(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    private fun readDocumentHead(context: Context, uri: Uri, offset: Int): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            var skipped = 0L
            while (skipped < offset) {
                val count = stream.skip((offset - skipped).toLong())
                if (count <= 0) break
                skipped += count
            }
            val bytes = ByteArray(IP_BIN_SIZE)
            var read = 0
            while (read < bytes.size) {
                val count = stream.read(bytes, read, bytes.size - read)
                if (count <= 0) break
                read += count
            }
            if (read < bytes.size) null else bytes
        }
    }.getOrNull()

    /**
     * Boot sector offset inside the track file. 2352-byte MODE1 sectors carry a
     * 16-byte sync/header, MODE2/2352 a 24-byte one, while 2048-byte images
     * store user data from the first byte.
     */
    private fun sectorDataOffset(trackType: String, sectorSize: Int): Int = when {
        trackType.startsWith("MODE2") && sectorSize >= 2352 -> 24
        trackType.startsWith("MODE2") && sectorSize >= 2336 -> 8
        trackType.startsWith("MODE1") && sectorSize >= 2352 -> 16
        sectorSize >= 2352 -> 16
        else -> 0
    }

    /** Data track of a GDI descriptor: file name plus boot-sector offset. */
    private fun gdiDataTrack(gdiText: String): Pair<String, Int>? = gdiText.lineSequence()
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 5 || parts[0].toIntOrNull() == null || parts[2].toIntOrNull() != 4) return@mapNotNull null
            val sectorSize = parts[3].toIntOrNull() ?: 2352
            parts[4] to sectorDataOffset("MODE1", sectorSize)
        }
        .firstOrNull()

    /**
     * The boot sector lives in the first MODE1/MODE2 track, never in an audio
     * track, so the cue is scanned for the data track's FILE directive.
     */
    private fun cueDataTrack(cueText: String): Pair<String, Int>? {
        var currentFile: String? = null
        for (line in cueText.lineSequence()) {
            val trimmed = line.removePrefix("\uFEFF").trim()
            val fileMatch = cueFileDirective.matchEntire(trimmed)
            if (fileMatch != null) {
                currentFile = (fileMatch.groups[1]?.value ?: fileMatch.groups[2]?.value)?.trim()
                continue
            }
            if (trimmed.startsWith("TRACK", ignoreCase = true)) {
                val parts = trimmed.split(Regex("\\s+"))
                val type = parts.getOrNull(2).orEmpty().uppercase(Locale.ROOT)
                if (type.startsWith("MODE1") || type.startsWith("MODE2")) {
                    val file = currentFile?.takeIf { it.isNotBlank() } ?: continue
                    val sectorSize = type.substringAfter('/', "2352").toIntOrNull() ?: 2352
                    return file to sectorDataOffset(type, sectorSize)
                }
            }
        }
        return null
    }

    private fun parseIpBin(bytes: ByteArray): GameMetadata? {
        if (bytes.size < IP_BIN_SIZE) return null
        val hardwareId = String(bytes, 0, 16, Charsets.US_ASCII)
        if (!hardwareId.startsWith("SEGA SEGAKATANA") && !hardwareId.startsWith("SEGA SEGASATURN")) {
            return null
        }
        val product = String(bytes, PRODUCT_OFFSET, PRODUCT_LENGTH, Charsets.US_ASCII).trim()
        val title = String(bytes, TITLE_OFFSET, TITLE_LENGTH, Charsets.US_ASCII)
            .substringBefore('\u0000')
            .trim()
        if (title.isBlank()) return null
        return GameMetadata(
            title = title,
            serial = product.takeIf { it.isNotBlank() },
            serialWithCrc = null,
            region = areaRegion(bytes),
        )
    }

    /** IP.BIN area symbols: J = Japan, U = USA, E = Europe. */
    private fun areaRegion(bytes: ByteArray): String? {
        val area = String(bytes, AREA_OFFSET, AREA_LENGTH, Charsets.US_ASCII).trim().uppercase(Locale.ROOT)
        if (area.isBlank()) return null
        return if (area.contains('E') && !area.contains('U') && !area.contains('J')) "PAL" else "NTSC"
    }

    @Suppress("unused")
    private fun littleInt(bytes: ByteArray, offset: Int): Int = ByteBuffer.wrap(bytes, offset, 4)
        .order(ByteOrder.LITTLE_ENDIAN).int
}
