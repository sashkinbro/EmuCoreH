package com.sbro.emucoreh.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Animatable
import android.os.Build
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeBackgroundImageDecoderInstrumentedTest {
    @Test fun decodesImagesAndAnimatedGifsAndRejectsInvalidFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "background-decode-${UUID.randomUUID()}")
        try {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            file.writeBytes(ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            })
            bitmap.recycle()
            val image = HomeBackgroundImageDecoder.decode(context, file, HomeBackgroundType.IMAGE)
            assertNotNull(image)
            assertTrue(image!!.intrinsicWidth > 0 && image.intrinsicHeight > 0)

            file.writeBytes(Base64.decode("R0lGODlhAgACAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAAgACAAAIBgABCAQQEAAh+QQBCgABACwAAAAAAgACAIEAAP8AAAAAAAAAAAAIBgABCAQQEAA7", Base64.DEFAULT))
            val animated = HomeBackgroundImageDecoder.decode(context, file, HomeBackgroundType.GIF)
            assertTrue(animated is Animatable)
            (animated as Animatable).start()
            assertTrue(animated.isRunning)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                val drawable = animated as android.graphics.drawable.Drawable
                val frame = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, 2, 2)
                drawable.draw(Canvas(frame))
                val firstPixel = frame.getPixel(0, 0)
                Thread.sleep(120)
                drawable.draw(Canvas(frame))
                assertNotEquals(firstPixel, frame.getPixel(0, 0))
                frame.recycle()
            }
            animated.stop()
            assertTrue(!animated.isRunning)

            file.writeBytes(byteArrayOf(1, 2, 3))
            assertNull(HomeBackgroundImageDecoder.decode(context, file, HomeBackgroundType.IMAGE))
            assertNull(HomeBackgroundImageDecoder.decode(context, file, HomeBackgroundType.GIF))
        } finally {
            file.delete()
        }
    }
}
