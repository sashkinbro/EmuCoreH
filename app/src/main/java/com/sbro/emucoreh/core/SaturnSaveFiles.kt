package com.sbro.emucoreh.core

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** The upstream core stores its internal backup RAM here; firmware is never a backup member. */
object SaturnSaveFiles {
    const val MAX_BYTES = 8L * 1024 * 1024
    fun directory(context: Context, dataRoot: String? = null): File =
        File(EmulatorStorage.runtimeDirectories(context, dataRoot).root, "saves/saturn")

    fun isBackupPath(relative: String): Boolean = relative == "yabasanshiro/backup.bin"

    fun stateIdentity(path: String): String = "SS-" + MessageDigest.getInstance("SHA-256")
        .digest(path.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02X".format(it) }
}
