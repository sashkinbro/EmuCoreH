package com.sbro.emucoreh.data.igdb

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Verify a new asset before atomically replacing an installed read-only catalog. */
internal fun installBundledCatalog(target: File, expectedHash: String, openAsset: () -> InputStream) {
    require(expectedHash.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid catalog asset hash" }
    val marker = File(target.parentFile, "${target.name}.sha256")
    if (target.isFile && target.length() > 0 && marker.isFile && marker.readText().trim() == expectedHash) return
    target.parentFile?.mkdirs()
    val temporary = File(target.parentFile, "${target.name}.installing")
    try {
        val hash = MessageDigest.getInstance("SHA-256")
        openAsset().use { input -> FileOutputStream(temporary).use { output ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        } }
        check(hash.digest().joinToString("") { "%02x".format(it) }.equals(expectedHash, true)) { "Catalog asset hash mismatch" }
        temporary.inputStream().use { input ->
            check(input.readNBytesCompat(16).contentEquals("SQLite format 3\u0000".toByteArray())) { "Invalid SQLite catalog" }
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        marker.writeText(expectedHash)
    } finally { temporary.delete() }
}

private fun InputStream.readNBytesCompat(size: Int): ByteArray {
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val count = read(bytes, offset, size - offset)
        if (count < 0) break
        offset += count
    }
    return bytes.copyOf(offset)
}
