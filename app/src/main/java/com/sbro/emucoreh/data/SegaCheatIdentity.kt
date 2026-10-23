package com.sbro.emucoreh.data

import java.security.MessageDigest
import java.util.Locale

/** Revision-specific source identity for cartridges without a readable product code. */
internal object SegaCheatIdentity {
    fun key(path: String, platform: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        return "JG-${platform.uppercase(Locale.ROOT)}-" + digest.take(16).joinToString("") { "%02X".format(it) }
    }
}
