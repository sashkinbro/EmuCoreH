package com.sbro.emucoreh.core

import android.graphics.Bitmap

internal fun createSaveStatePreviewBitmap(width: Int, height: Int): Bitmap =
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        // PSP framebuffer alpha may contain stencil/mask data. The displayed surface is opaque.
        // Using that data as transparency also corrupts PNG RGB during unpremultiplication.
        setHasAlpha(false)
    }
