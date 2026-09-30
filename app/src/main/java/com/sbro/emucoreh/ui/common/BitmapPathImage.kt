package com.sbro.emucoreh.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val TARGET_MAX_DIMENSION_PX = 384
private const val IMAGE_CACHE_MAX_BYTES = 32 * 1024 * 1024

private val imageLoadingSemaphore = Semaphore(4)

private val imageCache = object : LruCache<String, Bitmap>(IMAGE_CACHE_MAX_BYTES) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

@Composable
fun BitmapPathImage(
    imagePath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable (() -> Unit)? = null
) {
    val context = LocalContext.current
    val bitmapState = produceState<Bitmap?>(initialValue = null, key1 = imagePath) {
        value = loadBitmapSafely(context, imagePath)
    }

    val bitmap = bitmapState.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale
        )
    } else if (fallback != null) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            fallback()
        }
    }
}

private suspend fun loadBitmapSafely(context: Context, imagePath: String?): Bitmap? {
    val normalizedPath = imagePath?.trim().orEmpty()
    if (normalizedPath.isBlank()) return null

    imageCache.get(normalizedPath)?.let { return it }

    val bitmap = withContext(Dispatchers.IO) {
        imageLoadingSemaphore.withPermit {
            runCatching {
                when {
                    normalizedPath.startsWith("content://") -> decodeSampled {
                        context.contentResolver.openInputStream(normalizedPath.toUri())
                    }
                    normalizedPath.startsWith("http://") || normalizedPath.startsWith("https://") -> {
                        loadBitmapFromUrl(normalizedPath)
                    }
                    else -> decodeSampled {
                        val file = File(normalizedPath)
                        if (file.exists()) file.inputStream() else null
                    }
                }
            }.getOrNull()
        }
    }

    if (bitmap != null) {
        imageCache.put(normalizedPath, bitmap)
    }

    return bitmap
}

private fun loadBitmapFromUrl(url: String): Bitmap? {
    val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
        ?: return null
    return try {
        runCatching {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = true
            connection.doInput = true
            connection.setRequestProperty("User-Agent", "EmuCoreH/1.0")

            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream?.use { it.readBytes() } ?: return null
            decodeSampled { ByteArrayInputStream(bytes) }
        }.getOrNull()
    } finally {
        connection.disconnect()
    }
}

private fun decodeSampled(openStream: () -> InputStream?): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    var largestDimension = maxOf(bounds.outWidth, bounds.outHeight)
    while (largestDimension / 2 >= TARGET_MAX_DIMENSION_PX) {
        sampleSize *= 2
        largestDimension /= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return openStream()?.use { BitmapFactory.decodeStream(it, null, options) }
}
