// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import android.content.Context
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Only persistent cabinet NVRAM belongs in user backups; cached ROMs and temporary states do not. */
object Model3SaveFiles {
    const val MAX_NVRAM_BYTES = 16L * 1024 * 1024
    fun directory(context: Context, dataRoot: String? = null): File =
        File(EmulatorStorage.runtimeDirectories(context, dataRoot).root, "saves/supermodel")

    fun isBackupPath(relative: String): Boolean {
        val parts = relative.split('/')
        return parts.size == 2 && '\\' !in relative && ':' !in relative &&
            parts[1] == "nvram.bin" && parts[0].matches(Regex("[a-z0-9_]+")) &&
            Model3Romsets.find("${parts[0]}.zip") != null
    }

    fun backupFiles(root: File): List<Pair<String, File>> {
        val canonicalRoot = root.canonicalFile
        return Model3Romsets.games.mapNotNull { game ->
            val directory = File(canonicalRoot, game.name)
            val file = File(directory, "nvram.bin")
            if (!Files.isSymbolicLink(directory.toPath()) && !Files.isSymbolicLink(file.toPath()) &&
                directory.canonicalFile == directory.absoluteFile && file.isFile &&
                file.length() in 1..MAX_NVRAM_BYTES && file.canonicalFile.parentFile == directory.canonicalFile) {
                "${game.name}/nvram.bin" to file
            } else null
        }
    }

    fun restore(input: InputStream, root: File, relative: String) {
        require(isBackupPath(relative)) { "Invalid Model 3 NVRAM path" }
        val canonicalRoot = root.canonicalFile
        val setDirectory = File(canonicalRoot, relative.substringBefore('/'))
        require(!Files.isSymbolicLink(setDirectory.toPath()) && setDirectory.canonicalFile == setDirectory.absoluteFile &&
            !Files.isSymbolicLink(File(canonicalRoot, relative).toPath())) { "Invalid Model 3 save directory" }
        val target = File(canonicalRoot, relative).canonicalFile
        require(target.path.startsWith(canonicalRoot.path + File.separator)) { "Invalid Model 3 NVRAM path" }
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Unable to create Model 3 save directory" }
        val temporary = File.createTempFile(".nvram-", ".restoring", target.parentFile)
        try {
            temporary.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var size = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(count.toLong() <= MAX_NVRAM_BYTES - size) { "Model 3 NVRAM backup is too large" }
                    output.write(buffer, 0, count)
                    size += count
                }
                require(size > 0) { "Model 3 NVRAM backup is empty" }
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
