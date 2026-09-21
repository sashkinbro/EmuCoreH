package com.sbro.emucoreh.data

import android.content.Context
import android.net.Uri
import com.sbro.emucoreh.core.CoreRuntime
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class VmuFile(val name: String, val bytes: Long, val modifiedAt: Long)

/**
 * Flycast stores Visual Memory Units as 128 KiB `*.bin` images. With the
 * default per-game setting disabled they live in the core's system folder
 * (`<system>/dc/vmu_save_A1.bin` ...), which is what the manager lists.
 */
class VmuRepository(private val context: Context) {
    val saveDirectory: File
        get() = File(CoreRuntime.vmuDirPath)

    fun ensureDirectory(): Boolean = runCatching {
        val root = saveDirectory
        (root.isDirectory || root.mkdirs()) && root.isDirectory
    }.getOrDefault(false)

    fun vmus(): List<VmuFile> = saveDirectory.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".bin", ignoreCase = true) }
        .map { file -> VmuFile(file.name, file.length(), file.lastModified()) }
        .sortedBy { it.name.lowercase() }

    /** Creates the eight shared VMU images (A1/A2/B1/B2/C1/C2/D1/D2). */
    fun createDefaultVmus(): Boolean = runCatching {
        if (!ensureDirectory()) return false
        val empty = ByteArray(VMU_SIZE_BYTES)
        for (port in listOf("A1", "A2", "B1", "B2", "C1", "C2", "D1", "D2")) {
            val file = File(saveDirectory, "vmu_save_$port.bin")
            if (!file.exists()) file.outputStream().use { it.write(empty) }
        }
        true
    }.getOrDefault(false)

    fun backup(uri: Uri): Boolean = runCatching {
        val root = saveDirectory.canonicalFile
        context.contentResolver.openOutputStream(uri)?.use { output ->
            ZipOutputStream(output).use { zip ->
                root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".bin", true) }.forEach { file ->
                    zip.putNextEntry(ZipEntry("VMU/${file.name}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } != null
    }.getOrDefault(false)

    /** Imports VMU images from a backup. Existing files are preserved. */
    fun restore(uri: Uri): Boolean = runCatching {
        val root = saveDirectory.canonicalFile
        val buffer = ByteArray(64 * 1024)
        var entries = 0
        var totalBytes = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    require(entries <= 20_000)
                    val relative = entry.name.replace('\\', '/').substringAfterLast('/')
                    require(relative.endsWith(".bin", ignoreCase = true) && relative.isNotBlank())
                    val destination = File(root, relative).canonicalFile
                    require(destination.toPath().startsWith(root.toPath()))
                    if (entry.isDirectory) {
                        destination.mkdirs()
                    } else {
                        destination.parentFile?.mkdirs()
                        // Never clobber a newer save during a restore.
                        if (destination.exists()) {
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                totalBytes += count
                                require(totalBytes <= 1024L * 1024 * 1024)
                            }
                        } else {
                            destination.outputStream().use { output ->
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    totalBytes += count
                                    require(totalBytes <= 1024L * 1024 * 1024)
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } != null && entries > 0
    }.getOrDefault(false)

    private companion object {
        const val VMU_SIZE_BYTES = 128 * 1024
    }
}
