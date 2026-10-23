package com.sbro.emucoreh.data

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A failed ZIP read/CRC or size check must not truncate an installed save. */
internal fun restoreBackupSaveFile(input: InputStream, target: File, maxBytes: Long, allowEmpty: Boolean) {
    require(maxBytes > 0)
    val parent = requireNotNull(target.parentFile)
    check(parent.isDirectory || parent.mkdirs()) { "Unable to create save directory" }
    val temporary = File.createTempFile(".save-restore-", ".tmp", parent)
    try {
        temporary.outputStream().use { output ->
            val buffer = ByteArray(65536)
            var size = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count.toLong() <= maxBytes - size) { "Save backup exceeds size limit" }
                output.write(buffer, 0, count)
                size += count
            }
            require(size > 0 || allowEmpty) { "Save backup is empty" }
            output.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally { temporary.delete() }
}
