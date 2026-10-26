package com.sbro.emucoreh.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/** Cartridge/disc header metadata only; filename title cleanup remains the caller's fallback. */
object SegaMetadataReader {
    fun read(context: Context, path: String): GameMetadata? = runCatching { read(context, path, HashSet(), 0) }.getOrNull()

    private fun read(context: Context, path: String, active: MutableSet<String>, depth: Int): GameMetadata? {
        if (depth > 8 || !active.add(path)) return null
        val saf = path.startsWith("content://")
        val name = if (saf) DocumentPathResolver.getDisplayName(context, path) else File(path).name
        val extension = name.substringAfterLast('.').lowercase(Locale.ROOT)
        if (extension == "chd") {
            val metadata = if (saf) context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { descriptor ->
                NativeCoreBridge().saturnDiscMetadata("/proc/self/fd/${descriptor.fd}")
            } else NativeCoreBridge().saturnDiscMetadata(path)
            return metadata?.let { value ->
                val fields = value.split('\n', limit = 2)
                GameMetadata(fields.firstOrNull()?.trim()?.takeIf(String::isNotBlank) ?: File(name).nameWithoutExtension,
                    fields.getOrNull(1)?.trim()?.take(10)?.takeIf(String::isNotBlank))
            }
        }
        val input = if (saf) context.contentResolver.openInputStream(Uri.parse(path)) else File(path).inputStream()
        return input?.use { stream ->
            if (extension == "cue" || extension == "m3u") {
                val bytes = stream.prefix(1024 * 1024 + 1)
                if (bytes.size > 1024 * 1024) return@use null
                for (reference in SegaPlatformDetector.descriptorReferences(name, bytes.toString(Charsets.UTF_8))) {
                    val target = if (saf) DocumentPathResolver.resolveTrackDocument(context, Uri.parse(path), reference)?.uri?.toString()
                        else SegaPlatformDetector.safeLocalReference(File(path), reference)?.path
                    target?.let { read(context, it, active, depth + 1) }?.let { return@use it }
                }
                null
            } else if (extension == "zip") {
                ZipInputStream(stream).use { zip ->
                    repeat(256) {
                        val entry = zip.nextEntry ?: return@use null
                        if (!entry.isDirectory && GameFormats.isLaunchableName(entry.name)) return@use parseHeader(entry.name, zip.prefix(65536))
                        // Bound ignored content as well as the header read.
                        if (zip.prefix(1024 * 1024 + 1).size > 1024 * 1024) return@use null
                        zip.closeEntry()
                    }
                    null
                }
            } else parseHeader(name, stream.prefix(65536))
        }
    }

    fun parseHeader(name: String, source: ByteArray): GameMetadata? {
        val platform = SegaPlatformDetector.detectHeader(name, source)
        if (platform.isSaturn) {
            val offset = if (source.startsAt(16, "SEGA SEGASATURN")) 16 else 0
            return GameMetadata(source.ascii(offset + 0x60, 112) ?: File(name).nameWithoutExtension, source.ascii(offset + 0x20, 10))
        }
        if (platform !in setOf(EmulationPlatform.GENESIS, EmulationPlatform.SEGA_32X, EmulationPlatform.SEGA_CD)) return null
        var bytes = source
        var offset = if (source.startsAt(16, "SEGADISCSYSTEM")) 16 else 0
        if (source.startsAt(0x300, "SEGA")) offset = 512
        if (source.startsAt(0x100, "ESAG")) bytes = source.copyOf().also { swapped ->
            for (index in 0 until swapped.lastIndex step 2) {
                val first = swapped[index]; swapped[index] = swapped[index + 1]; swapped[index + 1] = first
            }
        }
        if (name.endsWith(".smd", true) && !bytes.startsAt(0x100 + offset, "SEGA") && bytes.size >= 16896) {
            bytes = ByteArray(16384).also { plain ->
                for (index in 0 until 8192) { plain[index * 2] = source[512 + 8192 + index]; plain[index * 2 + 1] = source[512 + index] }
            }
            offset = 0
        }
        if (!bytes.startsAt(offset + 0x100, "SEGA")) return null
        val title = bytes.ascii(offset + 0x150, 48) ?: bytes.ascii(offset + 0x120, 48)
            ?: File(name).nameWithoutExtension
                .replace(Regex("\\([^)]*\\)|\\[[^]]*\\]"), " ")
                .replace('_', ' ').replace(Regex("\\s+"), " ").trim()
                .ifBlank { File(name).nameWithoutExtension }
        val serial = bytes.ascii(offset + 0x180, 14)?.takeIf { value -> value.any(Char::isDigit) }
        return GameMetadata(title, serial)
    }

    private fun ByteArray.startsAt(offset: Int, value: String): Boolean = size >= offset + value.length && value.indices.all { this[offset + it] == value[it].code.toByte() }
    private fun ByteArray.ascii(offset: Int, count: Int): String? {
        if (size < offset + count) return null
        val field = copyOfRange(offset, offset + count)
        // Shift-JIS trail bytes can be printable ASCII. Keeping only those bytes
        // invents titles such as "F N K C D"; reject the whole field instead.
        if (field.any { it.toInt() != 0 && (it.toInt() and 0xff) !in 32..126 }) return null
        return field.map { if (it.toInt() in 32..126) it.toInt().toChar() else ' ' }
            .joinToString("").replace(Regex("\\s+"), " ").trim().takeIf { it.isNotBlank() }
    }
    private fun InputStream.prefix(limit: Int): ByteArray {
        val bytes = ByteArray(limit)
        var offset = 0
        while (offset < limit) { val count = read(bytes, offset, limit - offset); if (count <= 0) break; offset += count }
        return bytes.copyOf(offset)
    }
}
