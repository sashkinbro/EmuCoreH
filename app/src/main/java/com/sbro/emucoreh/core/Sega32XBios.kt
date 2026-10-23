package com.sbro.emucoreh.core

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

/** User-provided firmware only; this object never carries firmware payload bytes. */
object Sega32XBios {
    enum class Part(val fileName: String, val size: Int, val crc32: Long) {
        M68K("32X_G_BIOS.bin", 256, 0x5c12eae8L),
        MASTER("32X_M_BIOS.bin", 2048, 0xdd9c46b8L),
        SLAVE("32X_S_BIOS.bin", 1024, 0xbfda1fe5L)
    }

    fun directory(context: Context) = SegaCdBios.directory(context)

    fun isInstalled(context: Context, part: Part): Boolean = runCatching {
        val file = File(directory(context), part.fileName)
        file.isFile && file.length() == part.size.toLong() && isValid(file.readBytes(), part)
    }.getOrDefault(false)

    fun install(context: Context, uri: Uri, part: Part): Boolean = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            readValidated(input, part)
        } ?: return false
        val directory = directory(context).also { check(it.mkdirs() || it.isDirectory) }
        val temporary = File.createTempFile("${part.fileName}.", ".tmp", directory)
        try {
            temporary.outputStream().use { it.write(bytes) }
            Files.move(
                temporary.toPath(),
                File(directory, part.fileName).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } finally {
            temporary.delete()
        }
        true
    }.getOrDefault(false)

    internal fun isValid(bytes: ByteArray, part: Part): Boolean =
        bytes.size == part.size && CRC32().apply { update(bytes) }.value == part.crc32

    internal fun readValidated(input: InputStream, part: Part): ByteArray? {
        val output = ByteArrayOutputStream(part.size + 1)
        val buffer = ByteArray(8192)
        while (output.size() <= part.size) {
            val read = input.read(buffer, 0, minOf(buffer.size, part.size + 1 - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray().takeIf { isValid(it, part) }
    }
}
