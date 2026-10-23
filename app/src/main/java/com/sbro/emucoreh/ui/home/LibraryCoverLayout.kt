package com.sbro.emucoreh.ui.home

import com.sbro.emucoreh.core.EmulationPlatform

/** Match the case proportions rather than reserving a square around portrait artwork. */
internal fun libraryCoverAspectRatio(platform: String?, legacyAspect: Float): Float =
    when (EmulationPlatform.fromName(platform)) {
        EmulationPlatform.MODEL_3 -> 582f / 727f
        EmulationPlatform.SEGA_32X -> 521f / 730f
        EmulationPlatform.GAME_GEAR -> 577f / 730f
        EmulationPlatform.SEGA_CD -> 570f / 730f
        EmulationPlatform.GENESIS, EmulationPlatform.MASTER_SYSTEM, EmulationPlatform.SG_1000 -> 549f / 730f
        else -> legacyAspect
    }
