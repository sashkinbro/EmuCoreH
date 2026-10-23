package com.sbro.emucoreh.core

import android.content.Context
import java.io.File
import java.util.Locale

/** Explicit battery-save allowlist shared by local and Drive backup. */
object SegaSaveFiles {
    fun directory(context: Context, dataRoot: String? = null): File =
        File(EmulatorStorage.runtimeDirectories(context, dataRoot).root, "saves/jgenesis")

    fun isBackupPath(relative: String): Boolean {
        val parts = relative.split('/')
        return parts.size == 2 && '\\' !in relative && ':' !in relative &&
            EmulationPlatform.entries.any { it.isSega && it.name.lowercase(Locale.ROOT) == parts[0] } &&
            parts[1].isNotBlank() && !parts[1].startsWith('.') &&
            parts[1].substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("sav", "bram", "ramc")
    }
}
