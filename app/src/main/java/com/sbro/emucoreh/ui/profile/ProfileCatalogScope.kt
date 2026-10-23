package com.sbro.emucoreh.ui.profile

import com.sbro.emucoreh.data.igdb.catalogPlatformName
import java.util.Locale

/** Legacy arcade records need a verified cabinet identity, never a Dreamcast title guess. */
internal fun profileCatalogScope(
    platform: String?,
    arcade: Boolean,
    serial: String?,
    findArcadeBios: (String) -> String?
): String? {
    if (!platform.isNullOrBlank()) return catalogPlatformName(platform)
    if (!arcade) return catalogPlatformName(null)
    val key = serial?.trim()?.takeIf(String::isNotBlank) ?: return null
    return when (findArcadeBios(key)?.lowercase(Locale.ROOT)) {
        "naomi", "airlbios", "f355bios", "f355dlx", "hod2bios", "naomi-jp-d", "naomi-ko-h" -> "Sega Naomi"
        "naomi2" -> "Sega Naomi 2"
        "awbios" -> "Sammy Atomiswave"
        "segasp" -> "Sega System SP"
        else -> null
    }
}
