// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

internal data class Model3ActiveSavePaths(val launchPath: String, val identityPath: String) {
    fun matches(path: String, currentLaunchPath: String, currentIdentityPath: String, hasSession: Boolean): Boolean =
        hasSession && launchPath == currentLaunchPath && identityPath == currentIdentityPath &&
            (path == launchPath || path == identityPath)
}

/** Only CRC-verified launch preparation publishes here; inspection/import never changes it. */
internal class Model3PreparedSet {
    private var value: Pair<String, String>? = null
    @Synchronized fun record(path: String, setName: String) { value = path to setName }
    @Synchronized fun forPath(path: String): String? = value?.takeIf { it.first == path }?.second
    @Synchronized fun clear() { value = null }
}

/** Materializes a selected SAF ZIP and its registered parent chain without extracting game data. */
object Model3ContentPreparer {
    private val caches = HashMap<String, Model3ContentCache>()
    private val preparedSet = Model3PreparedSet()

    fun prepare(context: Context, path: String): String {
        preparedSet.clear()
        return try {
            val prepared = if (!path.startsWith("content://")) cacheFor(context).prepareFile(File(path))
                else cacheFor(context).prepare(path, documentSource(context))
            preparedSet.record(path, Model3Romsets.find(prepared.name)!!.name)
            prepared.absolutePath
        } catch (failure: Exception) {
            throw IOException("Unable to prepare Model 3 content: ${failure.message}", failure)
        }
    }

    internal fun preparedSetForPath(path: String): String? = preparedSet.forPath(path)

    /** Call only for launch/import actions, never library scans; SAF inspection may copy a bounded ZIP. */
    fun resolveExpectedSet(context: Context, path: String): String? = runCatching {
        val cache = cacheFor(context)
        if (path.startsWith("content://")) cache.resolveExpectedSet(path, documentSource(context))
        else cache.resolveFile(File(path))?.name
    }.getOrNull()

    private fun documentSource(context: Context) = object : Model3ContentSource {
        override fun displayName(path: String) = DocumentPathResolver.getDisplayName(context, path)
        override fun open(path: String) = context.contentResolver.openInputStream(Uri.parse(path))
        override fun resolveReference(path: String, reference: String) =
            DocumentPathResolver.resolveTrackDocument(context, Uri.parse(path), reference)?.uri?.toString()
    }

    /** Invoke after native unload, never while its ZIPs are in use. */
    fun releasePreparedContent(context: Context): Boolean { preparedSet.clear(); return cacheFor(context).release() }
    fun cleanupAbandonedContent(context: Context): Boolean { preparedSet.clear(); return cacheFor(context).cleanupAbandoned() }

    private fun cacheFor(context: Context): Model3ContentCache = synchronized(caches) {
        val directory = context.applicationContext.cacheDir
        caches.getOrPut(directory.canonicalPath) { Model3ContentCache(directory) }
    }
}
