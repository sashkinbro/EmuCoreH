/*
	Copyright 2022 flyinghead

	This file is part of Flycast.

    Flycast is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.

    Flycast is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Flycast.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sbro.emucoreh.data.boxart

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.sbro.emucoreh.core.DocumentPathResolver
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Kotlin port of Flycast's offline box art scraper: reads the 0GDTEX.PVR
 * texture stored on Dreamcast GD-ROMs and converts it to a bitmap. The ISO9660
 * primary volume descriptor is located through the data track referenced by
 * the GDI/CUE descriptor (or read straight from an ISO image), exactly like
 * core/core/ui/boxart/scraper.cpp does through Flycast's Disc and IsoFs.
 */
object DiscGdtexExtractor {

    private const val TAG = "DiscGdtexExtractor"
    private const val ISO_SECTOR_SIZE = 2048
    private const val GDROM_DATA_START_LBA = 45000L
    private const val MAX_ROOT_DIRECTORY_BYTES = 1L * 1024L * 1024L
    private const val MAX_GDTEX_BYTES = 4L * 1024L * 1024L
    private const val GDTEX_NAME = "0GDTEX.PVR"

    fun extractBitmap(context: Context, gamePath: String, fileName: String): Bitmap? {
        return runCatching {
            val track = resolveDataTrack(context, gamePath, fileName) ?: return null
            track.use { image ->
                val pvd = image.userData(image.baseIsoLba + 16, 1) ?: return null
                if (!isPrimaryVolumeDescriptor(pvd)) return null

                val rootExtent = leU32(pvd, 156 + 2).toLong()
                val rootSize = leU32(pvd, 156 + 10).toLong()
                if (rootExtent <= 0 || rootSize <= 0) return null
                val rootBytes = rootSize.coerceAtMost(MAX_ROOT_DIRECTORY_BYTES)
                val sectors = ((rootBytes + ISO_SECTOR_SIZE - 1) / ISO_SECTOR_SIZE).toInt()
                val root = image.userData(rootExtent, sectors) ?: return null

                val entry = findGdtexEntry(root) ?: return null
                val gdtexBytes = entry.size.toLong().coerceAtMost(MAX_GDTEX_BYTES)
                if (gdtexBytes <= 4) return null
                val fileSectors = ((gdtexBytes + ISO_SECTOR_SIZE - 1) / ISO_SECTOR_SIZE).toInt()
                val fileData = image.userData(entry.extent, fileSectors) ?: return null
                PvrDecoder.decode(fileData.copyOf(gdtexBytes.toInt()))
            }
        }.onFailure { error ->
            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.w(TAG, "Unable to extract 0GDTEX.PVR from $fileName", error)
        }.getOrNull()
    }

    private data class IsoEntry(val extent: Long, val size: Int)

    private fun findGdtexEntry(root: ByteArray): IsoEntry? {
        var index = 0
        while (index + 33 <= root.size) {
            val length = root[index].toInt() and 0xFF
            if (length == 0) {
                if (index % ISO_SECTOR_SIZE == 0) {
                    index += ISO_SECTOR_SIZE
                    if (index + 33 > root.size) return null
                    if (root[index].toInt() and 0xFF == 0) return null
                    continue
                }
                index = ((index + ISO_SECTOR_SIZE - 1) / ISO_SECTOR_SIZE) * ISO_SECTOR_SIZE
                if (index + 33 > root.size) return null
                continue
            }
            val nameLength = root[index + 32].toInt() and 0xFF
            if (nameLength in 1..(length - 33) && index + 33 + nameLength <= root.size) {
                val rawName = String(root, index + 33, nameLength, Charsets.US_ASCII)
                val name = rawName.substringBefore(';').trim()
                if (name.equals(GDTEX_NAME, ignoreCase = true)) {
                    val extent = leU32(root, index + 2).toLong()
                    val size = leU32(root, index + 10)
                    if (extent > 0 && size > 0) return IsoEntry(extent, size)
                }
            }
            index += length
        }
        return null
    }

    private fun isPrimaryVolumeDescriptor(pvd: ByteArray): Boolean {
        if (pvd.size < ISO_SECTOR_SIZE) return false
        if (pvd[0].toInt() != 1) return false
        if (pvd[1] != 'C'.code.toByte() || pvd[2] != 'D'.code.toByte() ||
            pvd[3] != '0'.code.toByte() || pvd[4] != '0'.code.toByte() || pvd[5] != '1'.code.toByte()
        ) {
            return false
        }
        return pvd[6].toInt() == 1
    }

    private fun resolveDataTrack(context: Context, gamePath: String, fileName: String): TrackImage? {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val fromUri = gamePath.startsWith("content://")
        return when (extension) {
            "gdi" -> resolveGdiTrack(context, gamePath, fromUri)
            "cue" -> resolveCueTrack(context, gamePath, fromUri)
            "iso", "bin" -> resolvePlainTrack(context, gamePath, fromUri)
            else -> null
        }
    }

    private fun resolveGdiTrack(context: Context, gamePath: String, fromUri: Boolean): TrackImage? {
        val descriptorUri = if (fromUri) Uri.parse(gamePath) else null
        val text = readDescriptorText(context, gamePath, fromUri) ?: return null
        for (line in text.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 6) continue
            val trackNumber = parts[0].toIntOrNull() ?: continue
            val trackType = parts[2].toIntOrNull() ?: continue
            if (trackType != 4) continue
            val sectorSize = parts[3].toIntOrNull() ?: 2352
            if (sectorSize != 2352 && sectorSize != 2048) continue
            val name = parts[4].trim().trim('"')
            if (name.isBlank()) continue
            val startLba = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: GDROM_DATA_START_LBA
            val fileOffset = parts[5].toLongOrNull() ?: 0L
            val dataOffset = sectorDataOffset("MODE1", sectorSize)
            if (fromUri) {
                val document = DocumentPathResolver.resolveTrackDocument(context, descriptorUri!!, name)
                if (document != null) {
                    return TrackImage(
                        source = UriBlockSource(context, document.uri),
                        baseIsoLba = startLba,
                        rawSectorSize = sectorSize,
                        dataOffset = dataOffset,
                        fileOffsetBytes = fileOffset
                    )
                }
            } else {
                val file = File(File(gamePath).parentFile, name)
                if (file.isFile) {
                    return TrackImage(
                        source = FileBlockSource(file),
                        baseIsoLba = startLba,
                        rawSectorSize = sectorSize,
                        dataOffset = dataOffset,
                        fileOffsetBytes = fileOffset
                    )
                }
            }
            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.d(TAG, "GDI track $trackNumber ($name) not accessible")
        }
        return null
    }

    private fun resolveCueTrack(context: Context, gamePath: String, fromUri: Boolean): TrackImage? {
        val descriptorUri = if (fromUri) Uri.parse(gamePath) else null
        val text = readDescriptorText(context, gamePath, fromUri) ?: return null
        var currentFile: String? = null
        var currentType: String? = null
        var isDataTrack = false
        // FAD tracking mirrors Flycast's cue parser: a GD-ROM cue switches the
        // running FAD to 45000 when it declares REM HIGH-DENSITY, and the track
        // file offset is the INDEX 01 position inside the current file.
        var currentFad = 150L
        var fileStartFad = 0L
        for (line in text.lineSequence()) {
            val trimmed = line.removePrefix("\uFEFF").trim()
            when {
                trimmed.startsWith("REM", ignoreCase = true) &&
                    trimmed.contains("HIGH-DENSITY", ignoreCase = true) -> {
                    currentFad = GDROM_DATA_START_LBA + 150L
                }
                trimmed.startsWith("FILE", ignoreCase = true) -> {
                    val name = trimmed.substringAfter('"', "").substringBefore('"')
                    currentFile = name.ifBlank {
                        trimmed.substringAfter("FILE ", "").substringBefore(' ').trim()
                    }.takeIf { it.isNotBlank() }
                    fileStartFad = currentFad
                }
                trimmed.startsWith("TRACK", ignoreCase = true) -> {
                    val type = trimmed.split(Regex("\\s+")).getOrNull(2).orEmpty().uppercase(Locale.ROOT)
                    isDataTrack = type.startsWith("MODE1") || type.startsWith("MODE2") ||
                        type.startsWith("CDI")
                    currentType = if (isDataTrack) type else null
                }
                isDataTrack && trimmed.startsWith("INDEX 01", ignoreCase = true) -> {
                    val file = currentFile ?: continue
                    val type = currentType ?: continue
                    val position = trimmed.substringAfter("INDEX 01").trim()
                    val indexFad = parseMsf(position) ?: 0L
                    val baseIsoLba = fileStartFad + indexFad - 150L
                    val declared = type.substringAfter('/', "2352").toIntOrNull() ?: 2352
                    val actualSectorSize = if (fromUri) declared else {
                        val candidate = File(File(gamePath).parentFile, file)
                        if (declared >= 2352 && candidate.isFile && candidate.length() % 2352L == 0L) 2352 else 2048
                    }
                    val dataOffset = sectorDataOffset(type, actualSectorSize)
                    val fileOffset = indexFad * actualSectorSize
                    if (fromUri) {
                        val document = DocumentPathResolver.resolveTrackDocument(context, descriptorUri!!, file)
                        if (document != null) {
                            return TrackImage(
                                source = UriBlockSource(context, document.uri),
                                baseIsoLba = baseIsoLba,
                                rawSectorSize = actualSectorSize,
                                dataOffset = dataOffset,
                                fileOffsetBytes = fileOffset
                            )
                        }
                    } else {
                        val candidate = File(File(gamePath).parentFile, file)
                        if (candidate.isFile) {
                            return TrackImage(
                                source = FileBlockSource(candidate),
                                baseIsoLba = baseIsoLba,
                                rawSectorSize = actualSectorSize,
                                dataOffset = dataOffset,
                                fileOffsetBytes = fileOffset
                            )
                        }
                    }
                }
            }
        }
        return null
    }

    private fun resolvePlainTrack(context: Context, gamePath: String, fromUri: Boolean): TrackImage? {
        return if (fromUri) {
            TrackImage(
                source = UriBlockSource(context, Uri.parse(gamePath)),
                baseIsoLba = 0L,
                rawSectorSize = ISO_SECTOR_SIZE,
                dataOffset = 0,
                fileOffsetBytes = 0L
            )
        } else {
            val file = File(gamePath)
            if (!file.isFile) return null
            val rawSectorSize = if (file.length() % 2352L == 0L && file.length() % 2048L != 0L) {
                2352
            } else {
                ISO_SECTOR_SIZE
            }
            TrackImage(
                source = FileBlockSource(file),
                baseIsoLba = 0L,
                rawSectorSize = rawSectorSize,
                dataOffset = if (rawSectorSize == 2352) sectorDataOffset("MODE1", 2352) else 0,
                fileOffsetBytes = 0L
            )
        }
    }

    private fun readDescriptorText(context: Context, gamePath: String, fromUri: Boolean): String? {
        return runCatching {
            if (fromUri) {
                context.contentResolver.openInputStream(Uri.parse(gamePath))?.bufferedReader()?.use { it.readText() }
            } else {
                File(gamePath).takeIf { it.isFile }?.readText()
            }
        }.getOrNull()
    }

    private fun parseMsf(value: String): Long? {
        val parts = value.split(':')
        if (parts.size != 3) return null
        val minutes = parts[0].toLongOrNull() ?: return null
        val seconds = parts[1].toLongOrNull() ?: return null
        val frames = parts[2].toLongOrNull() ?: return null
        return (minutes * 60 + seconds) * 75 + frames
    }

    private fun sectorDataOffset(trackType: String, sectorSize: Int): Int = when {
        (trackType.startsWith("MODE2") || trackType.startsWith("CDI")) && sectorSize >= 2352 -> 24
        (trackType.startsWith("MODE2") || trackType.startsWith("CDI")) && sectorSize >= 2336 -> 8
        trackType.startsWith("MODE1") && sectorSize >= 2352 -> 16
        sectorSize >= 2352 -> 16
        else -> 0
    }

    private class TrackImage(
        private val source: BlockSource,
        val baseIsoLba: Long,
        private val rawSectorSize: Int,
        private val dataOffset: Int,
        private val fileOffsetBytes: Long
    ) : AutoCloseable {

        fun userData(isoLba: Long, sectors: Int): ByteArray? {
            if (sectors <= 0) return null
            val relativeSector = isoLba - baseIsoLba
            if (relativeSector < 0) return null
            val offset = fileOffsetBytes + relativeSector * rawSectorSize
            val raw = source.readAt(offset, sectors * rawSectorSize) ?: return null
            if (raw.size < sectors * rawSectorSize) return null
            if (rawSectorSize == ISO_SECTOR_SIZE && dataOffset == 0) return raw
            val out = ByteArray(sectors * ISO_SECTOR_SIZE)
            for (i in 0 until sectors) {
                System.arraycopy(
                    raw,
                    i * rawSectorSize + dataOffset,
                    out,
                    i * ISO_SECTOR_SIZE,
                    ISO_SECTOR_SIZE
                )
            }
            return out
        }

        override fun close() {
            source.close()
        }
    }

    private interface BlockSource {
        fun readAt(offset: Long, length: Int): ByteArray?
        fun close()
    }

    private class FileBlockSource(private val file: File) : BlockSource {
        private var handle: RandomAccessFile? = null

        override fun readAt(offset: Long, length: Int): ByteArray? {
            if (offset < 0 || length <= 0) return null
            return runCatching {
                val raf = handle ?: RandomAccessFile(file, "r").also { handle = it }
                if (offset + length > raf.length()) return null
                val bytes = ByteArray(length)
                raf.seek(offset)
                raf.readFully(bytes)
                bytes
            }.getOrNull()
        }

        override fun close() {
            runCatching { handle?.close() }
            handle = null
        }
    }

    private class UriBlockSource(
        private val context: Context,
        private val uri: Uri
    ) : BlockSource {
        private var stream: java.io.InputStream? = null

        override fun readAt(offset: Long, length: Int): ByteArray? {
            if (offset < 0 || length <= 0) return null
            return runCatching {
                val input = stream ?: context.contentResolver.openInputStream(uri)?.also { stream = it }
                    ?: return null
                var skipped = 0L
                while (skipped < offset) {
                    val count = input.skip(offset - skipped)
                    if (count <= 0) return null
                    skipped += count
                }
                val bytes = ByteArray(length)
                var read = 0
                while (read < length) {
                    val count = input.read(bytes, read, length - read)
                    if (count < 0) break
                    read += count
                }
                if (read < length) null else bytes
            }.getOrNull()
        }

        override fun close() {
            runCatching { stream?.close() }
            stream = null
        }
    }

    private fun leU32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}
