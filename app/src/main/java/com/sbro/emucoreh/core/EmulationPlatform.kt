package com.sbro.emucoreh.core

import java.util.Locale

/** Runtime routing only: Flycast retains responsibility for Dreamcast and arcade content. */
enum class EmulationPlatform(val coreId: Int, val supportsDisc: Boolean = false) {
    DREAMCAST(0, true),
    GENESIS(1),
    SEGA_CD(1, true),
    SEGA_32X(1),
    MASTER_SYSTEM(1),
    GAME_GEAR(1),
    SG_1000(1),
    MODEL_3(2),
    SATURN(3, true);

    val isSega: Boolean get() = coreId == 1
    val isModel3: Boolean get() = this == MODEL_3
    val isSaturn: Boolean get() = this == SATURN
    val usesPlatformTouchLayouts: Boolean get() = isSega || isModel3 || isSaturn

    companion object {
        fun fromName(name: String?): EmulationPlatform? =
            entries.firstOrNull { it.name == name?.trim()?.uppercase(Locale.ROOT) }
    }
}
