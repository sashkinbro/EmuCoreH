package com.sbro.emucoreh.ui.common

internal data class CoverArtworkBounds(val left: Int, val top: Int, val width: Int, val height: Int)

/** Excludes the faint outer shadow of generated cases without trimming their artwork. */
internal fun opaqueCoverBounds(pixels: IntArray, width: Int, height: Int): CoverArtworkBounds? {
    require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (y in 0 until height) {
        for (x in 0 until width) {
            if ((pixels[y * width + x] ushr 24) >= 128) {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
    }
    return if (right < left) null else CoverArtworkBounds(left, top, right - left + 1, bottom - top + 1)
}
