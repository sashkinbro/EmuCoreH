package com.sbro.emucoreh.core

import android.content.Context
import android.net.Uri
import java.io.File

/** User-supplied Sega CD firmware, isolated from Flycast and backup roots. */
object SegaCdBios {
    const val SIZE = 128 * 1024
    enum class Region(val fileName: String) {
        US("bios_CD_U.bin"), EU("bios_CD_E.bin"), JP("bios_CD_J.bin")
    }

    fun directory(context: Context) = File(context.filesDir, "jgenesis/system")

    fun isInstalled(context: Context, region: Region): Boolean =
        directory(context).listFiles().orEmpty().any {
            it.name.equals(region.fileName, ignoreCase = true) && it.isFile && it.length() == SIZE.toLong()
        }

    fun install(context: Context, uri: Uri, region: Region): Boolean = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            // Limit memory and reject oversized files without reading the whole document.
            val output = java.io.ByteArrayOutputStream(SIZE + 1)
            val buffer = ByteArray(8192)
            while (output.size() <= SIZE) {
                val read = input.read(buffer, 0, minOf(buffer.size, SIZE + 1 - output.size()))
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: return false
        if (bytes.size != SIZE) return false
        val directory = directory(context).also { check(it.mkdirs() || it.isDirectory) }
        val temporary = File.createTempFile("bios-", ".tmp", directory)
        try {
            temporary.writeBytes(bytes)
            check(temporary.renameTo(File(directory, region.fileName)))
        } finally {
            temporary.delete()
        }
        true
    }.getOrDefault(false)
}
