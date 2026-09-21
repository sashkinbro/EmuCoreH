package com.sbro.emucoreh.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SaveStatePreviewTest {
    @Test
    fun framebufferMaskAlphaDoesNotWashOutPngColors() {
        val frame = createSaveStatePreviewBitmap(3, 1)
        val pixels = ByteBuffer.wrap(byteArrayOf(
            100, 50, 25, 0,
            40, 80, 120, 16,
            120, 90, 60, 32,
        ))
        // GPU readback copies raw RGBA bytes; RGB is opaque even when the PSP alpha is a mask.
        frame.copyPixelsFromBuffer(pixels)
        val bytes = ByteArrayOutputStream().use { output ->
            frame.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        try {
            assertFalse(decoded.hasAlpha())
            assertEquals(Color.rgb(100, 50, 25), decoded.getPixel(0, 0))
            assertEquals(Color.rgb(40, 80, 120), decoded.getPixel(1, 0))
            assertEquals(Color.rgb(120, 90, 60), decoded.getPixel(2, 0))
        } finally {
            decoded.recycle()
            frame.recycle()
        }
    }
}
