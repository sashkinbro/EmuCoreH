package com.sbro.emucoreh.core

/** Internal rendering scale is independent from the frontend presentation backend. */
val EmulationPlatform.supportsInternalResolution: Boolean get() = coreId == 0 || isModel3

val EmulationPlatform.maxInternalResolutionMultiplier: Int get() = when {
    isModel3 -> 4
    coreId == 0 -> UPSCALE_MAX.toInt()
    else -> 1
}

fun normalizeUpscaleForPlatform(value: Float, platform: EmulationPlatform): Float =
    normalizeUpscale(value, platform.maxInternalResolutionMultiplier)

internal fun internalResolutionCoreOption(platform: EmulationPlatform, preference: Float): Pair<String, String>? {
    if (platform.isModel3) {
        return "supermodel_internal_resolution" to "${normalizeUpscaleForPlatform(preference, platform).toInt()}x"
    }
    if (platform.coreId != 0) return null
    val option = FlycastCoreOptions.option("reicast_internal_resolution") ?: return null
    val targetWidth = Math.round(640f * preference).coerceAtLeast(640)
    val choice = option.choices.minByOrNull { candidate ->
        val width = candidate.value.substringBefore('x').toIntOrNull() ?: Int.MAX_VALUE
        Math.abs(width - targetWidth)
    }?.value ?: return null
    return option.key to choice
}
