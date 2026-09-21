package com.sbro.emucoreh.core

import kotlin.math.roundToInt

const val UPSCALE_MIN = 1.0f
// Fresh installs start at 2x: the PSP panel's native 480x272 looks soft on
// modern tablet screens, and the devices this runs on handle 2x comfortably.
const val UPSCALE_DEFAULT = 2.0f
// The bundled PPSSPP option table tops out at 4800x2720 (10x).
const val UPSCALE_MAX = 10.0f

private const val UPSCALE_STEP = 1.0f
private const val UPSCALE_NATIVE_MULTIPLIER = 1.0f
private const val UPSCALE_MAX_MULTIPLIER = UPSCALE_MAX

fun normalizeUpscale(value: Float, maxMultiplier: Int = UPSCALE_MAX_MULTIPLIER.roundToInt()): Float {
    val max = maxMultiplier.coerceAtLeast(UPSCALE_NATIVE_MULTIPLIER.roundToInt())
        .coerceAtMost(UPSCALE_MAX_MULTIPLIER.roundToInt())
        .toFloat()
    val stepped = (value / UPSCALE_STEP).roundToInt() * UPSCALE_STEP
    return stepped.coerceIn(UPSCALE_NATIVE_MULTIPLIER, max)
}

fun upscaleMultiplierValue(value: Float): Int = upscaleMultiplierKey(normalizeUpscale(value))

fun upscaleMultiplierKey(value: Float): Int = (normalizeUpscale(value) * 100f).roundToInt()

fun upscaleKeyToMultiplier(value: Int): Float = normalizeUpscale(value.toFloat() / 100f)

fun formatUpscaleLabel(value: Float, nativeLabel: String): String {
    val normalized = normalizeUpscale(value)
    return if (normalized == UPSCALE_NATIVE_MULTIPLIER) nativeLabel else "${normalized.roundToInt()}x"
}

/**
 * Integer scale options: the native label for 1x, then 2x, 3x, ... up to
 * [maxMultiplier]. The same labels are used by the global settings screen,
 * the game manager and the in-game menu so a single resolution value is
 * displayed identically everywhere.
 */
fun buildUpscaleOptions(nativeLabel: String, maxMultiplier: Int = UPSCALE_MAX_MULTIPLIER.roundToInt()): List<Pair<Int, String>> {
    val max = maxMultiplier.coerceIn(UPSCALE_NATIVE_MULTIPLIER.roundToInt(), UPSCALE_MAX_MULTIPLIER.roundToInt())
    return (1..max).map { multiplier ->
        upscaleMultiplierKey(multiplier.toFloat()) to formatUpscaleLabel(multiplier.toFloat(), nativeLabel)
    }
}
