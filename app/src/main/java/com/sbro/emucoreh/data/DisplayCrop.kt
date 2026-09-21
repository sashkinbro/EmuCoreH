package com.sbro.emucoreh.data

/** Source-frame crop in native pixels, applied before aspect-ratio scaling. */
data class DisplayCrop(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    fun sanitized(): DisplayCrop = DisplayCrop(
        left = left.coerceIn(MIN_PIXELS, MAX_PIXELS),
        top = top.coerceIn(MIN_PIXELS, MAX_PIXELS),
        right = right.coerceIn(MIN_PIXELS, MAX_PIXELS),
        bottom = bottom.coerceIn(MIN_PIXELS, MAX_PIXELS)
    )

    val presetPixels: Int
        get() = left.takeIf { it == top && it == right && it == bottom && it in PRESET_PIXELS } ?: -1

    val isDisabled: Boolean
        get() = left == 0 && top == 0 && right == 0 && bottom == 0

    companion object {
        const val MIN_PIXELS = 0
        const val MAX_PIXELS = 64
        val PRESET_PIXELS = listOf(0, 2, 4, 6, 8, 10)
        fun uniform(pixels: Int): DisplayCrop = DisplayCrop(pixels, pixels, pixels, pixels).sanitized()
        val None = DisplayCrop()
        val ThinEdges = DisplayCrop(left = 2, top = 2, right = 2, bottom = 2)
        val SafeEdges = DisplayCrop(left = 4, top = 4, right = 4, bottom = 4)
    }
}
