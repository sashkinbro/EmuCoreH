package com.sbro.emucoreh.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import java.security.MessageDigest

/** Unknown or unreadable content deliberately keeps the legacy Flycast route. */
object SegaPlatformDetector {
    private const val HEADER_BYTES = 65536
    private const val MAX_DESCRIPTOR_BYTES = 1024 * 1024
    private const val MAX_DEPTH = 8
    private val cartridgeExtensions = setOf("md", "gen", "smd", "32x", "sms", "gg", "sg", "bin")

    fun detect(context: Context, path: String): EmulationPlatform = runCatching {
        if (!path.startsWith("content://")) return@runCatching detectFile(File(path))
        detectDocument(context, path, mutableSetOf(), 0)
    }.getOrDefault(EmulationPlatform.DREAMCAST)

    fun detectFile(file: File): EmulationPlatform = detectFile(file, mutableSetOf(), 0)

    private fun detectFile(file: File, visited: MutableSet<String>, depth: Int): EmulationPlatform = runCatching {
        if (depth > MAX_DEPTH || !visited.add(file.canonicalPath)) return@runCatching EmulationPlatform.DREAMCAST
        val ext = extension(file.name)
        if (ext in setOf("gdi", "cdi", "iso", "7z", "dat", "lst")) return@runCatching EmulationPlatform.DREAMCAST
        if (ext == "cue" || ext == "m3u") {
            if (file.length() > MAX_DESCRIPTOR_BYTES) return@runCatching EmulationPlatform.DREAMCAST
            val references = descriptorReferences(file.name, file.readText())
            for (reference in references) {
                val target = safeLocalReference(file, reference) ?: continue
                val detected = detectFile(target, visited, depth + 1)
                if (detected == EmulationPlatform.SEGA_CD || (ext == "m3u" && detected.isSega)) return@runCatching detected
            }
            return@runCatching EmulationPlatform.DREAMCAST
        }
        if (ext == "zip") return@runCatching file.inputStream().use(::detectZip)
        val detected = file.inputStream().use { detectHeader(file.name, it.readPrefix(HEADER_BYTES)) }
        if (ext == "chd" && !detected.isSega) {
            val probe = runCatching { NativeCoreBridge().probeSegaDisc(file.absolutePath) }.getOrDefault(0)
            if (probe > 0) EmulationPlatform.SEGA_CD else detected
        } else detected
    }.getOrElse { detectName(file.name) }

    private fun detectDocument(context: Context, path: String, visited: MutableSet<String>, depth: Int): EmulationPlatform {
        if (depth > MAX_DEPTH || !visited.add(path)) return EmulationPlatform.DREAMCAST
        val name = DocumentPathResolver.getDisplayName(context, path)
        val ext = extension(name)
        if (ext in setOf("gdi", "cdi", "iso", "7z", "dat", "lst")) return EmulationPlatform.DREAMCAST
        if (ext == "chd") {
            DocumentPathResolver.resolveFilePath(context, path)?.let { direct ->
                if (!direct.startsWith("content://") && File(direct).isFile) return detectFile(File(direct))
            }
            val probe = runCatching {
                context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use {
                    NativeCoreBridge().probeSegaDisc("/proc/self/fd/${it.fd}")
                } ?: 0
            }.getOrDefault(0)
            if (probe > 0) return EmulationPlatform.SEGA_CD
        }
        return context.contentResolver.openInputStream(Uri.parse(path))?.use { stream ->
            when (ext) {
                "zip" -> detectZip(stream)
                "cue", "m3u" -> {
                    val bytes = stream.readPrefix(MAX_DESCRIPTOR_BYTES + 1)
                    if (bytes.size > MAX_DESCRIPTOR_BYTES) return@use EmulationPlatform.DREAMCAST
                    for (reference in descriptorReferences(name, bytes.toString(Charsets.UTF_8))) {
                        val target = DocumentPathResolver.resolveTrackDocument(context, Uri.parse(path), reference) ?: continue
                        val detected = detectDocument(context, target.uri.toString(), visited, depth + 1)
                        if (detected == EmulationPlatform.SEGA_CD || (ext == "m3u" && detected.isSega)) return@use detected
                    }
                    EmulationPlatform.DREAMCAST
                }
                else -> detectHeader(name, stream.readPrefix(HEADER_BYTES))
            }
        } ?: detectName(name)
    }

    fun detectName(name: String): EmulationPlatform = when (extension(name)) {
        "md", "gen", "smd" -> EmulationPlatform.GENESIS
        "32x" -> EmulationPlatform.SEGA_32X
        "sms" -> EmulationPlatform.MASTER_SYSTEM
        "gg" -> EmulationPlatform.GAME_GEAR
        "sg" -> EmulationPlatform.SG_1000
        else -> EmulationPlatform.DREAMCAST
    }

    fun detectHeader(name: String, bytes: ByteArray): EmulationPlatform {
        val ext = extension(name)
        if (ext in setOf("gdi", "cdi", "iso", "7z", "dat", "lst")) return EmulationPlatform.DREAMCAST
        if (listOf(0, 16).any { bytes.matches(it, "SEGA SEGAKATANA") }) return EmulationPlatform.DREAMCAST
        if (listOf(0, 16).any { bytes.matches(it, "SEGADISCSYSTEM") }) return EmulationPlatform.SEGA_CD
        // Never interpret arbitrary compressed/disc bytes as a cartridge ROM.
        if (ext in setOf("chd", "cue", "m3u", "zip")) return EmulationPlatform.DREAMCAST
        // Explicit hardware extensions must agree with the core's cartridge loader.
        if (ext in setOf("32x", "sms", "gg", "sg")) return detectName(name)
        if (bytes.matches(0x100, "SEGA 32X") || bytes.matches(0x100, "SEGA GENESIS 32X") || has32XSecurityProgram(bytes)) return EmulationPlatform.SEGA_32X
        if (listOf(0x100, 0x300).any { offset ->
            listOf("SEGA MEGA DRIVE", "SEGA GENESIS").any { bytes.matches(offset, it) || bytes.matchesWordSwapped(offset, it) }
        }) return EmulationPlatform.GENESIS
        for (offset in listOf(0x7ff0, 0x3ff0, 0x1ff0)) {
            if (bytes.matches(offset, "TMR SEGA") && bytes.size > offset + 15) {
                return if ((bytes[offset + 15].toInt() and 0xf0) shr 4 in 5..7) EmulationPlatform.GAME_GEAR else EmulationPlatform.MASTER_SYSTEM
            }
        }
        return detectName(name)
    }

    private fun detectZip(stream: InputStream): EmulationPlatform {
        var candidate: EmulationPlatform? = null
        ZipInputStream(stream).use { zip ->
            var entries = 0
            var inflatedBytes = 0L
            val drain = ByteArray(8192)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > 256) return EmulationPlatform.DREAMCAST
                if (!entry.isDirectory && extension(entry.name) in cartridgeExtensions) {
                    if (candidate != null || entry.size > 64L * 1024 * 1024) return EmulationPlatform.DREAMCAST
                    val detected = detectHeader(entry.name, zip.readPrefix(HEADER_BYTES))
                    if (!detected.isSega || detected.supportsDisc) return EmulationPlatform.DREAMCAST
                    candidate = detected
                }
                // closeEntry() alone can inflate an unbounded, highly compressed entry.
                while (true) {
                    val count = zip.read(drain)
                    if (count < 0) break
                    inflatedBytes += count
                    if (inflatedBytes > 64L * 1024 * 1024) return EmulationPlatform.DREAMCAST
                }
                zip.closeEntry()
            }
        }
        return candidate ?: EmulationPlatform.DREAMCAST
    }

    internal fun descriptorReferences(name: String, text: String): List<String> =
        if (extension(name) == "cue") DocumentPathResolver.cueReferences(text) else text.lineSequence()
            .map { it.trim().removePrefix("\uFEFF") }.filter { it.isNotEmpty() && !it.startsWith('#') }
            .map { it.removeSurrounding("\"").removeSurrounding("'") }.toList()

    internal fun safeLocalReference(descriptor: File, reference: String): File? {
        val normalized = reference.replace('\\', '/')
        if (normalized.startsWith('/') || Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(normalized)) return null
        val parent = descriptor.canonicalFile.parentFile ?: return null
        val target = File(parent, normalized).canonicalFile
        return target.takeIf { it.path.startsWith(parent.path + File.separator) && it.isFile }
    }

    private fun ByteArray.matches(offset: Int, value: String): Boolean =
        offset >= 0 && size >= offset + value.length && value.indices.all { this[offset + it] == value[it].code.toByte() }

    private fun ByteArray.matchesWordSwapped(offset: Int, value: String): Boolean =
        offset >= 0 && size >= offset + value.length + (value.length % 2) &&
            value.indices.all { this[offset + (it xor 1)] == value[it].code.toByte() }

    private fun has32XSecurityProgram(bytes: ByteArray): Boolean = bytes.size >= 0x800 &&
        MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(0x400, 0x800))
            .joinToString("") { "%02x".format(it) } == "41dbac9e1393c9c75e093e793c0b071c2ad90fa839de4bab4844ab9288dffeae"

    private fun InputStream.readPrefix(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var count = 0
        while (count < limit) {
            val next = read(buffer, count, limit - count)
            if (next < 0) break
            if (next == 0) break
            count += next
        }
        return buffer.copyOf(count)
    }

    private fun extension(name: String) = name.substringAfterLast('.').lowercase(Locale.ROOT)
}
