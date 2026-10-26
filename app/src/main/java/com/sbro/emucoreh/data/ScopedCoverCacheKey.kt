package com.sbro.emucoreh.data

import com.sbro.emucoreh.data.igdb.catalogPlatformName
import java.util.Locale

/** Preserve legacy cache filenames; different Sega consoles never share artwork by title. */
internal fun scopedCoverCacheKey(key: String, platform: String?): String {
    val canonical = catalogPlatformName(platform)
    return if (canonical in setOf("Genesis", "Sega CD", "32X", "Master System", "Game Gear", "SG-1000", "Sega Model 3", "Saturn")) {
        "sega_${canonical.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_")}_$key"
    } else key
}

/** Model 3 was previously treated as generic arcade, so its old managed covers have no safe identity. */
internal fun coverCacheFileMatchesPlatform(fileName: String, platform: String?): Boolean =
    catalogPlatformName(platform) !in setOf("Sega Model 3", "Saturn") || fileName.startsWith(scopedCoverCacheKey("", platform))
