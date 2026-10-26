package com.sbro.emucoreh.core

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Beetle Saturn stores per-content backup RAM, RTC and cartridge NV in this directory. */
object SaturnSaveFiles {
    const val MAX_BYTES = 8L * 1024 * 1024
    /** Beetle Saturn v1.22.2 Mednafen state container version. */
    const val STATE_VERSION = 0x00102202
    fun directory(context: Context, dataRoot: String? = null): File =
        File(EmulatorStorage.runtimeDirectories(context, dataRoot).root, "saves/saturn")

    fun isBackupPath(relative: String): Boolean {
        if (relative.isBlank() || relative.startsWith('.') || '/' in relative || '\\' in relative) return false
        val name = relative.lowercase(Locale.ROOT)
        return name.endsWith(".bkr") || name.endsWith(".smpc") || name.endsWith(".bcr")
    }

    fun stateIdentity(path: String): String = "SS-" + MessageDigest.getInstance("SHA-256")
        .digest(path.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02X".format(it) }
}
