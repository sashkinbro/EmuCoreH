package com.sbro.emucoreh.data

import com.sbro.emucoreh.data.igdb.catalogPlatformName
import java.security.MessageDigest
import java.util.Locale

internal fun playerGamePlatform(record: Map<String, Any?>): String? =
    (record["p"] as? String)?.trim()?.takeIf { it.isNotBlank() }

/** Legacy Flycast profile keys are stable; additional consoles have independent totals. */
internal fun playerGameKey(serial: String?, gamePath: String, platform: String?): String {
    val serialKey = serial?.trim()?.uppercase(Locale.ROOT)?.replace(Regex("[^A-Z0-9_-]"), "_")?.takeIf { it.isNotBlank() }
    val identity = serialKey ?: "P_" + MessageDigest.getInstance("SHA-256").digest(gamePath.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(32)
    val system = catalogPlatformName(platform)
    return if (system in setOf("Genesis", "Sega CD", "32X", "Master System", "Game Gear", "SG-1000", "Saturn"))
        "S_${system.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "_")}_$identity" else identity
}
