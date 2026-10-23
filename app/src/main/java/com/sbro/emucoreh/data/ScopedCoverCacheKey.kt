package com.sbro.emucoreh.data

import com.sbro.emucoreh.data.igdb.catalogPlatformName
import java.util.Locale

/** Preserve legacy cache filenames; different Sega consoles never share artwork by title. */
internal fun scopedCoverCacheKey(key: String, platform: String?): String {
    val canonical = catalogPlatformName(platform)
    return if (canonical in setOf("Genesis", "Sega CD", "32X", "Master System", "Game Gear", "SG-1000")) {
        "sega_${canonical.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_")}_$key"
    } else key
}
