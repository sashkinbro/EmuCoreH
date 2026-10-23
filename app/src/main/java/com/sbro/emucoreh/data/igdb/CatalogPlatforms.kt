package com.sbro.emucoreh.data.igdb

import java.util.Locale

/** Database spelling is stable across runtime enums, filters and scoped cover indexes. */
fun catalogPlatformName(platform: String?): String = when (platform?.trim()?.uppercase(Locale.ROOT)) {
    null, "DREAMCAST" -> "Dreamcast"
    "GENESIS", "MEGA DRIVE", "SEGA MEGA DRIVE/GENESIS" -> "Genesis"
    "SEGA_CD", "SEGA CD" -> "Sega CD"
    "SEGA_32X", "SEGA 32X", "32X" -> "32X"
    "MASTER_SYSTEM", "MASTER SYSTEM" -> "Master System"
    "GAME_GEAR", "GAME GEAR" -> "Game Gear"
    "SG_1000", "SG-1000" -> "SG-1000"
    "NAOMI", "SEGA NAOMI" -> "Sega Naomi"
    "NAOMI_2", "SEGA NAOMI 2" -> "Sega Naomi 2"
    "ATOMISWAVE", "SAMMY ATOMISWAVE" -> "Sammy Atomiswave"
    "MODEL_3", "MODEL3", "MODEL 3", "SEGA MODEL 3" -> "Sega Model 3"
    else -> platform.trim()
}
