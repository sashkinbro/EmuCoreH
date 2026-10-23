package com.sbro.emucoreh.data

import android.graphics.BitmapFactory
import java.io.File
import java.io.InputStream

internal const val MAX_SAVE_PREVIEW_BYTES = 16L * 1024L * 1024L

internal fun isSaveStatePreviewName(name: String): Boolean =
    name == File(name).name && '\\' !in name && ':' !in name &&
        name.endsWith(".rstate.png", ignoreCase = true) && !name.contains(".resume.", ignoreCase = true)

internal fun isValidSaveStatePreview(file: File): Boolean = runCatching {
    if (!file.isFile || file.length() !in 1..MAX_SAVE_PREVIEW_BYTES) return@runCatching false
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    val actual = ByteArray(8)
    file.inputStream().use { input ->
        var offset = 0
        while (offset < actual.size) {
            val count = input.read(actual, offset, actual.size - offset)
            if (count <= 0) return@runCatching false
            offset += count
        }
    }
    if (!actual.contentEquals(signature)) return@runCatching false
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    options.outWidth in 1..4096 && options.outHeight in 1..4096 &&
        options.outWidth.toLong() * options.outHeight <= 16L * 1024L * 1024L
}.getOrDefault(false)

/** Invalid or oversized thumbnails never replace an installed preview or invalidate a valid state. */
internal fun copySaveStatePreview(input: InputStream, target: File): Boolean {
    target.parentFile?.mkdirs()
    val temporary = File.createTempFile(".save-preview-", ".tmp", target.parentFile)
    return try {
        runCatching {
            var total = 0L
            val buffer = ByteArray(8192)
            temporary.outputStream().use { output ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_SAVE_PREVIEW_BYTES) { "Save preview exceeds size limit" }
                    output.write(buffer, 0, count)
                }
            }
            check(isValidSaveStatePreview(temporary))
            check(temporary.renameTo(target))
        }.isSuccess
    } finally { temporary.delete() }
}
