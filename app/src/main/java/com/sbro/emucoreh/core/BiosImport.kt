package com.sbro.emucoreh.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class BiosInstallResult(
    val dreamcast: Int = 0,
    val arcade: Int = 0,
    val segaCd: Int = 0,
    val sega32x: Int = 0
) {
    val total: Int get() = dreamcast + arcade + segaCd + sega32x
    val installed: Boolean get() = total > 0
}

/** Imports every BIOS family from one user-selected folder. */
object BiosImport {
    private const val MAX_FILES = 256
    private const val MAX_DIRECTORIES = 64
    private val arcadeExtensions = setOf("zip", "7z")

    fun hasAnyBios(context: Context): Boolean =
        DreamcastBios.hasAnyBios(flycastSystemDir(context)) ||
            SegaCdBios.Region.entries.any { SegaCdBios.isInstalled(context, it) } ||
            Sega32XBios.Part.entries.any { Sega32XBios.isInstalled(context, it) }

    fun installSelection(context: Context, uri: Uri): BiosInstallResult =
        installSelection(
            context,
            uri,
            EmulatorStorage.flycastSystemDir(context).absolutePath,
            Sega32XBios.directory(context).absolutePath
        )

    internal fun installSelection(
        context: Context,
        uri: Uri,
        flycastSystemDir: String,
        segaSystemDir: String
    ): BiosInstallResult {
        if (DocumentsContract.isTreeUri(uri)) {
            val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
                ?: return BiosInstallResult()
            return installFromDocument(context, root, flycastSystemDir, segaSystemDir)
        }
        val size = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L
        val name = DocumentPathResolver.getDisplayName(context, uri.toString())
        val counts = Counts()
        installFile(context, uri, name, size, flycastSystemDir, segaSystemDir, counts)
        return counts.result()
    }

    internal fun installFromDocument(
        context: Context,
        root: DocumentFile,
        flycastSystemDir: String,
        segaSystemDir: String
    ): BiosInstallResult {
        val counts = Counts()
        walk(context, root, flycastSystemDir, segaSystemDir, counts, Budget())
        return counts.result()
    }

    private fun walk(
        context: Context,
        source: DocumentFile,
        flycastSystemDir: String,
        segaSystemDir: String,
        counts: Counts,
        budget: Budget
    ) {
        if (!budget.enterDirectory()) return
        for (child in runCatching { source.listFiles() }.getOrDefault(emptyArray())) {
            if (child.isDirectory || child.type == DocumentsContract.Document.MIME_TYPE_DIR) {
                walk(context, child, flycastSystemDir, segaSystemDir, counts, budget)
                continue
            }
            if (!budget.checkFile()) continue
            val name = runCatching { child.name }.getOrNull().orEmpty().ifBlank {
                DocumentPathResolver.getDisplayName(context, child.uri.toString())
            }
            val size = runCatching { child.length() }.getOrDefault(-1L)
            installFile(context, child.uri, name, size, flycastSystemDir, segaSystemDir, counts)
        }
    }

    private fun installFile(
        context: Context,
        uri: Uri,
        name: String,
        size: Long,
        flycastSystemDir: String,
        segaSystemDir: String,
        counts: Counts
    ) {
        Sega32XBios.partForFileName(name)?.let { part ->
            if (Sega32XBios.installTo(context, uri, part, File(segaSystemDir))) counts.sega32x++
            return
        }
        SegaCdBios.regionForFileName(name)?.let { region ->
            if (SegaCdBios.installTo(context, uri, region, File(segaSystemDir))) counts.segaCd++
            return
        }
        // Some providers expose the three 32X dumps under unrelated names.
        // Identify them by their unique sizes and verify the CRC before use.
        if (Sega32XBios.installDetectedTo(context, uri, size, File(segaSystemDir)) != null) {
            counts.sega32x++
            return
        }
        if (DreamcastBios.supportedBiosTargetName(name, size) == null) return
        val installed = DreamcastBios.import(context, uri, flycastSystemDir) ?: return
        if (installed.extension.lowercase() in arcadeExtensions) counts.arcade++ else counts.dreamcast++
    }

    private fun flycastSystemDir(context: Context): String =
        EmulatorStorage.flycastSystemDir(context).absolutePath

    private class Counts {
        var dreamcast = 0
        var arcade = 0
        var segaCd = 0
        var sega32x = 0

        fun result() = BiosInstallResult(dreamcast, arcade, segaCd, sega32x)
    }

    private class Budget {
        private var files = 0
        private var directories = 0

        fun checkFile(): Boolean {
            if (files >= MAX_FILES) return false
            files++
            return true
        }

        fun enterDirectory(): Boolean {
            if (directories >= MAX_DIRECTORIES) return false
            directories++
            return true
        }
    }
}
