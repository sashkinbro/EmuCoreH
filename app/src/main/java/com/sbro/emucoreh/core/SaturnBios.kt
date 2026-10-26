package com.sbro.emucoreh.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** User firmware only. Checksums are documented by Libretro's Saturn core documentation. */
object SaturnBios {
    const val SIZE = 512 * 1024
    const val FILE_NAME = "saturn_bios.bin"
    private val checksums = setOf(
        "af5828fdff51384f99b3c4926be27762", // Japan 1.00
        "85ec9ca47d8f6807718151cbcca8b964", // Japan 1.01
        "3240872c70984b6cbfda1586cab68dbe" // US/Europe
    )

    fun directory(context: Context) = File(context.filesDir, "saturn/system")

    internal fun isValid(bytes: ByteArray): Boolean = bytes.size == SIZE &&
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) } in checksums

    internal fun readValidated(input: InputStream): ByteArray? {
        val bytes = input.readBytesBounded(SIZE + 1)
        return bytes.takeIf(::isValid)
    }

    fun isInstalled(context: Context): Boolean = runCatching {
        File(directory(context), FILE_NAME).inputStream().use(::readValidated) != null
    }.getOrDefault(false)

    fun requireInstalled(context: Context): String {
        if (!isInstalled(context)) throw IOException("SATURN_BIOS_REQUIRED: Import your Saturn BIOS (saturn_bios.bin, sega_101.bin or mpr-17933.bin) in Settings > Library > BIOS.")
        return File(directory(context), FILE_NAME).absolutePath
    }

    internal fun installDetected(context: Context, uri: Uri, size: Long): Boolean = runCatching {
        if (size >= 0 && size != SIZE.toLong()) return false
        val bytes = context.contentResolver.openInputStream(uri)?.use(::readValidated) ?: return false
        val directory = directory(context)
        check(directory.mkdirs() || directory.isDirectory)
        val temporary = File.createTempFile("saturn-bios-", ".tmp", directory)
        try {
            temporary.writeBytes(bytes)
            Files.move(temporary.toPath(), File(directory, FILE_NAME).toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
        true
    }.getOrDefault(false)

    private fun InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(limit)
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            if (count == 0) {
                val byte = read()
                if (byte < 0) break
                output.write(byte)
            } else output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
