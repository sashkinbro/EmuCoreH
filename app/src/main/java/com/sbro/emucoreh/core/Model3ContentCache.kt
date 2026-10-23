// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal interface Model3ContentSource {
    fun displayName(path: String): String
    fun open(path: String): InputStream?
    fun resolveReference(path: String, reference: String): String?
}

/** A fresh directory per launch preserves parent ZIP names and never reuses a running game's files. */
internal class Model3ContentCache(
    private val cacheDirectory: File,
    private val maxZipBytes: Long = 256L * 1024 * 1024,
    private val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
    private val identify: (File, Model3Game, (Model3Game) -> File?) -> Model3Game? = Model3ContentIdentity::resolve,
) {
    private val root = File(cacheDirectory, "model3-content")
    private val active = linkedSetOf<File>()

    /** Direct files need staging only when their identified native names or parent names differ. */
    @Synchronized fun prepareFile(file: File): File {
        val source = fileSource(file)
        val selected = resolveFile(file) ?: throw IOException("Model 3 program CRCs are incomplete or ambiguous")
        var game = selected
        val visited = mutableSetOf<String>()
        var canonicalFamily = file.name == "${game.name}.zip"
        while (game.parent.isNotBlank()) {
            check(visited.add(game.name) && visited.size <= 16) { "Recursive Model 3 parent chain" }
            game = requireSet("${game.parent}.zip")
            val parent = File(file.parentFile, "${game.name}.zip")
            canonicalFamily = canonicalFamily && parent.isFile && parent.canRead() &&
                parent.length() in 1..maxZipBytes && parent.canonicalFile.name == "${game.name}.zip"
        }
        if (canonicalFamily) return file.canonicalFile
        return prepare(file.absolutePath, source)
    }

    /** Cheap central-directory inspection of direct content; no retained identity cache. */
    @Synchronized fun resolveFile(file: File): Model3Game? {
        requireReadableFile(file)
        val source = fileSource(file)
        return identify(file, requireSet(file.name)) { game ->
            resolveParent(file.absolutePath, source, game)?.let(::File)?.also(::requireReadableFile)
        }
    }

    private fun fileSource(file: File) = object : Model3ContentSource {
        override fun displayName(path: String) = File(path).name
        override fun open(path: String): InputStream {
            val source = File(path)
            requireReadableFile(source)
            return source.inputStream()
        }
        override fun resolveReference(path: String, reference: String): String? =
            file.parentFile?.listFiles()?.firstOrNull { it.name.equals(reference, true) && it.isFile }?.absolutePath
    }

    private fun requireReadableFile(file: File) {
        require(file.isFile && file.canRead() && file.length() in 1..maxZipBytes) {
            "Model 3 ZIP is missing, unreadable or too large"
        }
    }

    private fun resolveParent(path: String, source: Model3ContentSource, game: Model3Game): String? =
        Model3Romsets.sourceNames(game).asSequence()
            .mapNotNull { source.resolveReference(path, it) }
            .firstOrNull { candidate -> runCatching { requireSet(source.displayName(candidate)).name == game.name }.getOrDefault(false) }

    @Synchronized fun prepare(path: String, source: Model3ContentSource): File = stage(path, source, true).first

    /** Explicit import inspection uses its own bounded files and leaves active launch files alone. */
    @Synchronized fun resolveExpectedSet(path: String, source: Model3ContentSource): String? {
        val staged = stage(path, source, false)
        val directory = staged.first.parentFile!!
        return try { staged.second.name } finally {
            active -= directory
            check(removeManagedDirectory(directory)) { "Unable to remove Model 3 inspection content" }
        }
    }

    private fun stage(path: String, source: Model3ContentSource, includeParents: Boolean): Pair<File, Model3Game> {
        val selected = requireSet(source.displayName(path))
        ensureRoot()
        check(cleanupAbandoned()) { "Unable to clear abandoned Model 3 content" }
        val directory = File(root, "session-${UUID.randomUUID()}")
        check(directory.mkdir()) { "Unable to create Model 3 content directory" }
        active += directory
        try {
            var total = 0L
            fun copy(targetPath: String, fileName: String): File {
                val destination = File(directory, fileName)
                require(destination.parentFile == directory) { "Invalid Model 3 content name" }
                val partial = File(directory, "$fileName.partial")
                source.open(targetPath)?.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var size = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(count.toLong() <= maxZipBytes - size && count.toLong() <= maxTotalBytes - total) {
                                "Model 3 ZIP content exceeds its cache limit"
                            }
                            output.write(buffer, 0, count)
                            size += count
                            total += count
                        }
                        require(size > 0) { "Model 3 ZIP is empty" }
                    }
                } ?: throw IOException("Cannot read Model 3 ZIP: $fileName")
                Files.move(partial.toPath(), destination.toPath())
                return destination
            }
            val copiedParents = mutableMapOf<String, File>()
            fun parent(game: Model3Game): File? = copiedParents[game.name] ?: resolveParent(path, source, game)?.let {
                copy(it, "${game.name}.zip").also { file -> copiedParents[game.name] = file }
            }
            val provisional = copy(path, ".selected.zip")
            val actual = identify(provisional, selected, ::parent)
                ?: throw IOException("Model 3 program CRCs are incomplete or ambiguous")
            val destination = File(directory, "${actual.name}.zip")
            // A parent's ZIP may have been examined while evaluating a split clone.
            copiedParents.remove(actual.name)?.let { check(it.delete()) }
            Files.move(provisional.toPath(), destination.toPath())
            val requiredParents = mutableSetOf<String>()
            if (includeParents) {
                var game = actual
                while (game.parent.isNotBlank()) {
                    check(requiredParents.add(game.parent) && requiredParents.size <= 16) { "Recursive Model 3 parent chain" }
                    game = requireSet("${game.parent}.zip")
                    parent(game) ?: throw IOException("Cannot access parent ZIP ${game.name}.zip; grant access to its containing folder")
                }
            }
            copiedParents.filterKeys { it !in requiredParents }.values.forEach { check(it.delete()) }
            return destination to actual
        } catch (failure: Exception) {
            active -= directory
            if (!removeManagedDirectory(directory)) failure.addSuppressed(IOException("Unable to remove partial Model 3 content"))
            throw failure
        }
    }

    /** Caller must unload the core before releasing its launch directories. */
    @Synchronized fun release(): Boolean {
        active.clear()
        return cleanupAbandoned()
    }

    @Synchronized fun cleanupAbandoned(): Boolean {
        if (!root.exists()) return true
        ensureRoot()
        var success = true
        root.listFiles().orEmpty().forEach { directory ->
            if (sessionName.matches(directory.name) && directory !in active) {
                success = removeManagedDirectory(directory) && success
            }
        }
        return success
    }

    private fun ensureRoot() {
        check(!Files.isSymbolicLink(root.toPath()) && root.canonicalFile.parentFile == cacheDirectory.canonicalFile) {
            "Model 3 cache must stay inside app storage"
        }
        check(root.isDirectory || root.mkdirs()) { "Unable to create Model 3 cache" }
    }

    private fun removeManagedDirectory(directory: File): Boolean = runCatching {
        if (!sessionName.matches(directory.name) || Files.isSymbolicLink(directory.toPath()) ||
            directory.canonicalFile.parentFile != root.canonicalFile) return@runCatching false
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(path: Path, failure: IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.deleteIfExists(path)
                return FileVisitResult.CONTINUE
            }
        })
        true
    }.getOrDefault(false)

    companion object {
        private val sessionName = Regex("session-[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
        internal fun requireSet(fileName: String): Model3Game {
            require(fileName.matches(Regex("[A-Za-z0-9_]+\\.[zZ][iI][pP]"))) { "Model 3 content must have a safe ROM-set ZIP basename" }
            return Model3Romsets.find(fileName) ?: throw IOException("Unknown Model 3 ROM-set ZIP")
        }
    }
}
