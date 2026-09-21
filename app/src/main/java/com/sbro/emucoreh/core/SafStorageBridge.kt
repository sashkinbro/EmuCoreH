package com.sbro.emucoreh.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.Keep
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Maps SAF documents to native VFS paths. Only metadata is cached; game bytes stay in place. */
@Keep
object SafStorageBridge {
    private const val PREFIX = "/__emucoreh_saf__/"
    private data class Entry(val uri: Uri, val name: String, val directory: Boolean, val size: Long)
    private data class Mount(val context: Context, val root: Entry, val entries: ConcurrentHashMap<String, Entry>)
    private val mounts = ConcurrentHashMap<String, Mount>()

    fun prepare(context: Context, path: String): String? = runCatching {
        if (!path.startsWith("content://")) return path
        val app = context.applicationContext
        val source = Uri.parse(path)
        val file = query(app, source) ?: return null
        val trees = buildList {
            if (DocumentsContract.isTreeUri(source)) add(source)
            app.contentResolver.persistedUriPermissions.filter { it.isReadPermission }
                .map { it.uri }.filter { it.authority == source.authority }.forEach { add(it) }
        }.distinctBy { runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull() }
        for (tree in trees) {
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            val root = query(app, rootUri)?.takeIf { it.directory } ?: continue
            val mount = Mount(app, root, ConcurrentHashMap())
            mount.entries[""] = root
            val sourceId = runCatching { DocumentsContract.getDocumentId(source) }.getOrNull() ?: continue
            // Document IDs are opaque. Ask the provider for the ancestry first.
            val ancestry = runCatching { DocumentsContract.findDocumentPath(app.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, sourceId))?.path }.getOrNull()
            val start = ancestry?.indexOf(rootId) ?: -1
            var relative: String? = if (start >= 0) {
                val names = ancestry!!.drop(start + 1).map { id ->
                    query(app, DocumentsContract.buildDocumentUriUsingTree(tree, id))?.name ?: return@map ""
                }
                names.takeIf { it.all(::safeName) }?.joinToString("/")
            } else null
            // ExternalStorageProvider uses hierarchical IDs, but this is not assumed for other providers.
            if (relative == null && source.authority == "com.android.externalstorage.documents" &&
                sourceId.startsWith(rootId.trimEnd('/') + "/")) {
                relative = sourceId.removePrefix(rootId.trimEnd('/') + "/")
            }
            if (relative == null) relative = find(mount, root, "", sourceId, HashSet())
            if (relative != null && relative.split('/').all(::safeName)) {
                mount.entries[relative] = file.copy(uri = DocumentsContract.buildDocumentUriUsingTree(tree, sourceId))
                val key = key(rootUri.toString())
                mounts[key] = mount
                return "$PREFIX$key/$relative"
            }
        }
        // A single-file grant still supports disc images. Sibling access requires a folder grant.
        if (!safeName(file.name)) return null
        val key = key(source.toString())
        mounts[key] = Mount(app, file, ConcurrentHashMap(mapOf(file.name to file)))
        "$PREFIX$key/${file.name}"
    }.getOrNull()

    private fun key(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(16).joinToString("") { "%02x".format(it) }

    private fun safeName(value: String) = value.isNotEmpty() && value != "." && value != ".." &&
        '/' !in value && '\\' !in value && '\u0000' !in value

    private fun query(context: Context, uri: Uri): Entry? = runCatching {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
            if (!it.moveToFirst()) null else Entry(uri, it.getString(0),
                it.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR, it.getLong(2))
        }
    }.getOrNull()

    private fun children(mount: Mount, entry: Entry): List<Entry>? = runCatching {
        if (!entry.directory) return null
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(entry.uri, DocumentsContract.getDocumentId(entry.uri))
        mount.context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    if (safeName(name)) add(Entry(DocumentsContract.buildDocumentUriUsingTree(entry.uri,
                        cursor.getString(0)), name, cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        cursor.getLong(3)))
                }
            }
        }
    }.getOrNull()

    private fun find(mount: Mount, parent: Entry, prefix: String, id: String, visited: MutableSet<String>): String? {
        if (!visited.add(DocumentsContract.getDocumentId(parent.uri))) return null
        for (child in children(mount, parent) ?: return null) {
            val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            mount.entries[relative] = child
            if (DocumentsContract.getDocumentId(child.uri) == id) return relative
            if (child.directory) find(mount, child, relative, id, visited)?.let { return it }
        }
        return null
    }

    private fun resolve(path: String): Pair<Mount, Entry>? {
        if (!path.startsWith(PREFIX)) return null
        val parts = path.removePrefix(PREFIX).split('/').filter { it.isNotEmpty() && it != "." }
        val mount = mounts[parts.firstOrNull()] ?: return null
        val segments = ArrayList<String>()
        for (part in parts.drop(1)) {
            if (part == "..") { if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex) }
            else { if (!safeName(part)) return null; segments.add(part) }
        }
        val relative = segments.joinToString("/")
        mount.entries[relative]?.let { return mount to it }
        var entry = mount.root
        var current = ""
        for (segment in segments) {
            current = if (current.isEmpty()) segment else "$current/$segment"
            entry = mount.entries[current] ?: children(mount, entry)?.let { list ->
                list.firstOrNull { it.name == segment } ?: list.singleOrNull { it.name.equals(segment, true) }
            }?.also { mount.entries[current] = it } ?: return null
        }
        return mount to entry
    }

    @JvmStatic fun open(path: String): Int = runCatching {
        val (mount, entry) = resolve(path) ?: return -1
        if (entry.directory) return -1
        mount.context.contentResolver.openFileDescriptor(entry.uri, "r")?.detachFd() ?: -1
    }.getOrDefault(-1)

    /** VFS stat flags and the full 64-bit size; no image content is read here. */
    @JvmStatic fun stat(path: String): LongArray? = runCatching {
        val (_, entry) = resolve(path) ?: return null
        longArrayOf(if (entry.directory) 3 else 1, entry.size)
    }.getOrNull()

    @JvmStatic fun list(path: String): Array<String>? = runCatching {
        val (mount, entry) = resolve(path) ?: return null
        children(mount, entry)?.map { (if (it.directory) "d" else "f") + it.name }?.toTypedArray()
    }.getOrNull()
}
