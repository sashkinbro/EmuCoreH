package com.sbro.emucoreh.data

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import java.io.File
import kotlin.math.max

internal object HomeBackgroundImageDecoder {
    fun decode(context: Context, file: File, type: HomeBackgroundType): Drawable? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeModern(file)
        } else if (type == HomeBackgroundType.GIF) {
            Movie.decodeFile(file.absolutePath)?.let(::MovieDrawable)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            }
            BitmapFactory.decodeFile(file.absolutePath, options)?.let { BitmapDrawable(context.resources, it) }
        }
    }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeModern(file: File): Drawable =
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height))
        }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (max(width, height) / sample > 2048) sample *= 2
        return sample
    }
}

private class MovieDrawable(private val movie: Movie) : Drawable(), Animatable {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var running = false
    private var startTime = 0L
    private val nextFrame = Runnable { invalidateSelf() }

    override fun draw(canvas: Canvas) {
        if (movie.width() <= 0 || movie.height() <= 0) return
        val duration = movie.duration().takeIf { it > 0 } ?: 1000
        val elapsed = if (running) SystemClock.uptimeMillis() - startTime else 0L
        movie.setTime((elapsed % duration).toInt())
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width().toFloat() / movie.width(), bounds.height().toFloat() / movie.height())
        movie.draw(canvas, 0f, 0f, paint)
        canvas.restoreToCount(save)
        if (running) scheduleSelf(nextFrame, SystemClock.uptimeMillis() + 16L)
    }

    override fun start() {
        if (running) return
        running = true
        startTime = SystemClock.uptimeMillis()
        invalidateSelf()
    }

    override fun stop() {
        running = false
        unscheduleSelf(nextFrame)
    }

    override fun isRunning(): Boolean = running
    override fun getIntrinsicWidth(): Int = movie.width()
    override fun getIntrinsicHeight(): Int = movie.height()
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
