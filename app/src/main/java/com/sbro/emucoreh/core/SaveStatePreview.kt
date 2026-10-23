package com.sbro.emucoreh.core

import android.graphics.Bitmap

internal fun createSaveStatePreviewBitmap(width: Int, height: Int): Bitmap =
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        // The emulated framebuffer alpha may contain stencil/mask data. The
        // displayed surface is opaque. Using that data as transparency also
        // corrupts PNG RGB during unpremultiplication.
        setHasAlpha(false)
    }

internal fun createSoftwareSaveStatePreviewBitmap(frame: IntArray?): Bitmap? {
    if (frame == null || frame.size < 3) return null
    val width = frame[0]
    val height = frame[1]
    if (width !in 1..4096 || height !in 1..4096 || width.toLong() * height + 2 != frame.size.toLong()) return null
    return createSaveStatePreviewBitmap(width, height).apply {
        setPixels(frame, 2, width, 0, 0, width, height)
    }
}
