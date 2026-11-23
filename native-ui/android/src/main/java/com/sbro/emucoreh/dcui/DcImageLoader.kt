package com.sbro.emucoreh.dcui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Asynchronous off-GL-thread image decoder for box art, quick-save PNG thumbnails,
 * and decoded 32x32 VMU file icons.
 *
 * Calls from the C++ renderer enqueue work onto a bounded 2-thread pool and return
 * immediately so scrolling stays locked at 60 fps even on weak devices.
 */
object DcImageLoader {
    private val lock = Any()
    private val inFlight = HashSet<String>()
    private val generation = AtomicInteger(0)

    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "DcUiImageLoader").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    @JvmStatic
    fun requestDecode(path: String, maxDimension: Int) {
        if (path.isBlank()) return
        synchronized(lock) {
            if (!inFlight.add(path)) return
        }
        val gen = generation.get()
        val targetDim = maxDimension.coerceIn(32, 512)
        executor.execute {
            try {
                if (generation.get() != gen) return@execute
                val decoded = decodeRgbaInternal(path, targetDim)
                if (generation.get() != gen) return@execute
                if (decoded != null) {
                    DcShellNative.nativeNotifyImageDecoded(
                        path,
                        decoded.width,
                        decoded.height,
                        decoded.rgba
                    )
                } else {
                    DcShellNative.nativeNotifyImageDecoded(path, 0, 0, null)
                }
                DcShellHost.onRequestRender()
            } catch (_: Throwable) {
                runCatching { DcShellNative.nativeNotifyImageDecoded(path, 0, 0, null) }
            } finally {
                synchronized(lock) {
                    inFlight.remove(path)
                }
            }
        }
    }

    fun cancelPending() {
        generation.incrementAndGet()
        synchronized(lock) {
            inFlight.clear()
        }
    }

    private data class DecodedImage(val width: Int, val height: Int, val rgba: ByteArray)

    private fun decodeRgbaInternal(path: String, maxDim: Int): DecodedImage? {
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) return null

        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            var sampleSize = 1
            val longest = max(bounds.outWidth, bounds.outHeight)
            while (longest / (sampleSize * 2) >= maxDim) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bitmap = BitmapFactory.decodeFile(path, decodeOpts) ?: return@runCatching null
            try {
                val w = bitmap.width
                val h = bitmap.height
                val curLongest = max(w, h)
                if (curLongest > maxDim) {
                    val scale = maxDim.toFloat() / curLongest.toFloat()
                    val dstW = (w * scale).roundToInt().coerceAtLeast(1)
                    val dstH = (h * scale).roundToInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(bitmap, dstW, dstH, true)
                    if (scaled !== bitmap) {
                        bitmap.recycle()
                        bitmap = scaled
                    }
                }
                if (bitmap.config != Bitmap.Config.ARGB_8888) {
                    val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    if (converted != null) {
                        if (converted !== bitmap) bitmap.recycle()
                        bitmap = converted
                    }
                }

                val outW = bitmap.width
                val outH = bitmap.height
                val numPixels = outW * outH
                val pixels = IntArray(numPixels)
                bitmap.getPixels(pixels, 0, outW, 0, 0, outW, outH)

                val rgba = ByteArray(numPixels * 4)
                var offset = 0
                for (i in 0 until numPixels) {
                    val argb = pixels[i]
                    rgba[offset] = ((argb ushr 16) and 0xFF).toByte()
                    rgba[offset + 1] = ((argb ushr 8) and 0xFF).toByte()
                    rgba[offset + 2] = (argb and 0xFF).toByte()
                    rgba[offset + 3] = ((argb ushr 24) and 0xFF).toByte()
                    offset += 4
                }
                DecodedImage(outW, outH, rgba)
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }
}
