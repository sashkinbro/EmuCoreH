package com.sbro.emucoreh.core

/**
 * Startup performance profiles used by analytics dimensions. EmuCoreH ships a single
 * tuned Flycast configuration, so every install reports the safe profile.
 */
object PerformanceProfiles {
    const val SAFE = 0
    const val FAST = 1

    fun normalize(profileId: Int): Int {
        return if (profileId == FAST) FAST else SAFE
    }
}
