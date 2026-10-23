package com.sbro.emucoreh.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Ordinary I/O behind the Android provider adapter, also usable by filesystem sources. */
internal interface JGenesisContentSource {
    fun displayName(path: String): String
    fun open(path: String): InputStream?
    fun resolveReference(path: String, reference: String): String?
}

/** A single emulator session owns its files, including previous discs needed for swap rollback. */
internal class JGenesisContentCache(
    private val cacheDirectory: File,
    private val maxBytes: Long = 8L * 1024 * 1024 * 1024,
) {
    private val root = File(cacheDirectory, "jgenesis-content")
    private var session: Session? = null

    @Synchronized fun prepare(path: String, source: JGenesisContentSource): File {
        ensureRoot()
        val current = session ?: run {
            check(cleanupAbandoned()) { "Unable to remove abandoned Sega content cache" }
            Session(File(root, "session-${UUID.randomUUID()}").apply {
                check(mkdir()) { "Unable to create Sega content cache" }
            }).also { session = it }
        }
        return current.prepare(path, source)
    }

    /** Caller must first unload/destroy the native core. */
    @Synchronized fun release(): Boolean {
        session = null
        return cleanupAbandoned()
    }

    /** Safe even while a session is tracked: its directory is always excluded. */
    @Synchronized fun cleanupAbandoned(): Boolean {
        if (!root.exists()) return true
        ensureRoot()
        var success = true
        root.listFiles().orEmpty().forEach { child ->
            if (managedName.matches(child.name) && child != session?.directory) {
                success = removeManagedDirectory(child) && success
            }
        }
        return success
    }

    private fun ensureRoot() {
        check(!Files.isSymbolicLink(root.toPath()) && root.canonicalFile.parentFile == cacheDirectory.canonicalFile) {
            "Sega content cache must stay inside the app cache directory"
        }
        check(root.isDirectory || root.mkdirs()) { "Unable to create Sega content cache" }
    }

    private fun removeManagedDirectory(directory: File): Boolean = runCatching {
        // Never follow a substituted session directory or any symlinks inside it.
        if (Files.isSymbolicLink(directory.toPath()) || directory.canonicalFile.parentFile != root.canonicalFile) return@runCatching false
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.deleteIfExists(directory)
                return FileVisitResult.CONTINUE
            }
        })
        true
    }.getOrDefault(false)

    private inner class Session(val directory: File) {
        private val prepared = HashMap<String, File>()
        private var copiedBytes = 0L

        fun prepare(path: String, source: JGenesisContentSource): File {
            prepared[path]?.takeIf { it.isFile }?.let { return it }
            val previous = prepared.toMap()
            val previousBytes = copiedBytes
            val created = LinkedHashSet<File>()
            return try {
                materialize(path, source, HashSet(), 0, created)
            } catch (failure: Exception) {
                // A failed launch/swap must not remove the current disc or leak partial copies.
                prepared.clear()
                prepared.putAll(previous)
                created.forEach { file -> runCatching { Files.deleteIfExists(file.toPath()) }.exceptionOrNull()?.let(failure::addSuppressed) }
                copiedBytes = previousBytes + created.filter { it.exists() }.sumOf { it.length() }
                throw failure
            }
        }

        private fun materialize(
            path: String, source: JGenesisContentSource, active: MutableSet<String>, depth: Int,
            created: MutableSet<File>,
        ): File {
            require(depth <= MAX_DEPTH && active.add(path)) { "Recursive or overly nested descriptor" }
            try {
                prepared[path]?.let { return it }
                require(prepared.size < MAX_FILES) { "Too many prepared Sega content files" }
                val name = source.displayName(path)
                val extension = name.substringAfterLast('.', "bin").lowercase(Locale.ROOT)
                val safeExtension = extension.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
                val hash = MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
                val destination = File(directory, "$hash.$safeExtension")
                source.open(path)?.use { input ->
                    if (extension == "cue" || extension == "m3u") {
                        val text = readDescriptor(input)
                        val references = references(name, text)
                        val resolved = references.associateWith { resolve(source, path, it) }
                        if (extension == "m3u") {
                            val checked = HashSet<String>()
                            resolved.values.forEach { validate(it, source, HashSet(), checked, depth + 1) }
                            val first = materialize(resolved.getValue(references.first()), source, active, depth + 1, created)
                            prepared[path] = first
                            return first
                        }
                        val targets = resolved.mapValues { (_, target) -> materialize(target, source, active, depth + 1, created) }
                        val rewritten = JGenesisContentPreparer.rewriteCue(text) { reference -> targets.getValue(reference).name }
                        rewritten.byteInputStream().use { copy(it, destination, created) }
                    } else copy(input, destination, created)
                } ?: throw IOException("Cannot read content: $name")
                prepared[path] = destination
                return destination
            } finally { active.remove(path) }
        }

        private fun copy(input: InputStream, destination: File, created: MutableSet<File>) {
            val partial = File(directory, "${destination.name}.partial")
            created += partial
            partial.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(count.toLong() <= maxBytes - copiedBytes) { "Prepared Sega content exceeds the 8 GiB session cache limit" }
                    output.write(buffer, 0, count)
                    copiedBytes += count
                }
            }
            Files.move(partial.toPath(), destination.toPath())
            created.remove(partial)
            created += destination
        }
    }

    private fun validate(path: String, source: JGenesisContentSource, active: MutableSet<String>, checked: MutableSet<String>, depth: Int) {
        require(depth <= MAX_DEPTH && active.add(path)) { "Recursive or overly nested descriptor" }
        try {
            if (!checked.add(path)) return
            require(checked.size <= MAX_FILES) { "Too many referenced Sega content files" }
            source.open(path)?.use { input ->
                val name = source.displayName(path)
                val extension = name.substringAfterLast('.').lowercase(Locale.ROOT)
                if (extension == "cue" || extension == "m3u") {
                    references(name, readDescriptor(input)).forEach { reference ->
                        validate(resolve(source, path, reference), source, active, checked, depth + 1)
                    }
                }
            } ?: throw IOException("Cannot access playlist content: ${source.displayName(path)}")
        } finally { active.remove(path) }
    }

    private fun resolve(source: JGenesisContentSource, path: String, reference: String): String {
        JGenesisContentPreparer.requireSafeReference(reference)
        return source.resolveReference(path, reference)
            ?: throw IOException("Cannot access descriptor member: $reference. Grant access to the containing folder.")
    }

    private fun references(name: String, text: String): List<String> {
        val entries = if (name.endsWith(".cue", true)) JGenesisContentPreparer.cueReferences(text)
            else SegaPlatformDetector.descriptorReferences(name, text)
        require(entries.isNotEmpty() && entries.size <= MAX_FILES) { "Descriptor has no files or too many entries" }
        return entries
    }

    private fun readDescriptor(input: InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= MAX_DESCRIPTOR_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_DESCRIPTOR_BYTES + 1 - output.size()))
            if (count < 0) break
            if (count > 0) output.write(buffer, 0, count)
        }
        require(output.size() <= MAX_DESCRIPTOR_BYTES) { "Descriptor is too large" }
        return output.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF")
    }

    companion object {
        private const val MAX_DESCRIPTOR_BYTES = 1024 * 1024
        private const val MAX_FILES = 256
        private const val MAX_DEPTH = 8
        private val managedName = Regex("(?:session-)?[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
