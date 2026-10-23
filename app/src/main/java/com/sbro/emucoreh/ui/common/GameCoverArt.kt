package com.sbro.emucoreh.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.sbro.emucoreh.ui.theme.neon.neonShape

/**
 * Square box-art tile, like Flycast's box art game list: the artwork keeps its
 * own proportions and is fitted inside the tile without being cropped.
 */
val LocalGameCoverAspectRatio = androidx.compose.runtime.staticCompositionLocalOf { 1f }
val GameCoverAspectRatio: Float
    @Composable get() = LocalGameCoverAspectRatio.current

fun isGenerated3dCover(coverPath: String?): Boolean =
    coverPath?.replace('\\', '/')?.let { path ->
        val localCache3d = path.contains("/game-covers/") &&
            (path.endsWith("_3d.png") || path.endsWith("_3d.jpg") || path.endsWith(".webp"))
        val remote3d = (path.contains("/covers/3d/") || path.contains("/arcade/3d/")) &&
            (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".webp"))
        localCache3d || remote3d || path.endsWith(".webp")
    } == true

/** 3D covers render with transparent padding, so rounding would cut their corners. */
fun gameCoverShape(coverPath: String?, roundedShape: Shape): Shape =
    if (isGenerated3dCover(coverPath)) RectangleShape else roundedShape

/** 3D covers must be fitted whole; regular covers fill and crop their frame. */
fun gameCoverContentScale(coverPath: String?, defaultScale: ContentScale): ContentScale =
    if (isGenerated3dCover(coverPath)) ContentScale.Fit else defaultScale

private const val COVER_LOAD_MAX_ATTEMPTS = 4
private const val COVER_LOAD_RETRY_DELAY_MS = 750L

private val imageLoadingSemaphore = Semaphore(4)
private const val DEFAULT_COVER_DECODE_WIDTH = 512
private const val DEFAULT_COVER_DECODE_HEIGHT = 512

@Composable
fun GameCoverArt(
    coverPath: String?,
    fallbackTitle: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    loadEnabled: Boolean = true,
    matchImageAspectRatio: Boolean = false,
    showTitleWhileLoading: Boolean = true,
    shimmerWhileLoading: Boolean = true,
    decodeWidth: Int = DEFAULT_COVER_DECODE_WIDTH,
    decodeHeight: Int = DEFAULT_COVER_DECODE_HEIGHT,
    trimTransparentPadding: Boolean = false,
    onAspectRatioResolved: ((Float) -> Unit)? = null
) {
    val context = LocalContext.current
    val cacheKey: (String) -> String = remember(decodeWidth, decodeHeight, trimTransparentPadding) {
        { path -> "$path@${decodeWidth}x${decodeHeight}:trim=$trimTransparentPadding" }
    }
    var bitmap by remember(coverPath, decodeWidth, decodeHeight, trimTransparentPadding) {
        mutableStateOf(coverPath?.let { getCachedBitmap(cacheKey(it)) })
    }
    var loadedPath by remember(coverPath, decodeWidth, decodeHeight, trimTransparentPadding) {
        mutableStateOf(coverPath?.takeIf { getCachedBitmap(cacheKey(it)) != null })
    }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(coverPath, loadEnabled, decodeWidth, decodeHeight, trimTransparentPadding) {
        if (coverPath.isNullOrBlank()) {
            bitmap = null
            loadedPath = null
            isLoading = false
            return@LaunchedEffect
        }

        val key = cacheKey(coverPath)
        if (!loadEnabled) {
            bitmap = getCachedBitmap(key)
            loadedPath = coverPath.takeIf { bitmap != null }
            isLoading = false
            return@LaunchedEffect
        }

        if (loadedPath == coverPath && bitmap != null) {
            isLoading = false
            return@LaunchedEffect
        }

        getCachedBitmap(key)?.let { cached ->
            bitmap = cached
            loadedPath = coverPath
            isLoading = false
            return@LaunchedEffect
        }

        isLoading = true
        var attempt = 0
        while (true) {
            val loadedBitmap = withContext(Dispatchers.IO) {
                imageLoadingSemaphore.withPermit {
                    loadBitmap(context, coverPath, decodeWidth, decodeHeight)?.let {
                        if (trimTransparentPadding) trimCoverTransparency(it) else it
                    }
                }
            }
            if (loadedBitmap != null) {
                putCachedBitmap(key, loadedBitmap)
                loadedPath = coverPath
                bitmap = loadedBitmap
                isLoading = false
                return@LaunchedEffect
            }
            if (attempt >= COVER_LOAD_MAX_ATTEMPTS) {
                isLoading = false
                return@LaunchedEffect
            }
            attempt++
            // Cover files are repaired by the background sync; keep trying for
            // a while so a transient miss does not blank the artwork until the
            // next app restart.
            delay(COVER_LOAD_RETRY_DELAY_MS * attempt)
        }
    }

    val currentAspectRatioCallback by rememberUpdatedState(onAspectRatioResolved)
    LaunchedEffect(bitmap) {
        val loaded = bitmap ?: return@LaunchedEffect
        if (loaded.height > 0) {
            currentAspectRatioCallback?.invoke(loaded.width.toFloat() / loaded.height.toFloat())
        }
    }

    val currentBitmap = bitmap
    val imageModifier = if (matchImageAspectRatio) {
        modifier.aspectRatio(currentBitmap?.let { it.width.toFloat() / it.height } ?: (16f / 9f))
    } else modifier
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap.asImageBitmap(),
            contentDescription = fallbackTitle,
            contentScale = contentScale,
            modifier = imageModifier
        )
    } else {
        Box(
            modifier = imageModifier
                .clip(neonShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .shimmer(showShimmer = isLoading && shimmerWhileLoading),
            contentAlignment = Alignment.Center
        ) {
            if (!isLoading || showTitleWhileLoading) {
                Text(
                    text = fallbackTitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .wrapContentSize(Alignment.Center)
                )
            }
        }
    }
}

private val coverBitmapCache = object : LruCache<String, Bitmap>(100 * 1024 * 1024) { // 100 MB cache
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

fun clearCoverImageMemoryCache() {
    coverBitmapCache.evictAll()
    // Let the next remote request prune/reconcile its freshly cleared disk directory again.
    remoteCachePruned.set(false)
}

fun invalidateCoverImage(path: String?) {
    if (path.isNullOrBlank()) return
    coverBitmapCache.snapshot().keys
        .filter { key -> key == path || key.startsWith("$path@") }
        .forEach { key -> coverBitmapCache.remove(key) }
}

private fun getCachedBitmap(path: String): Bitmap? = coverBitmapCache.get(path)

private fun putCachedBitmap(path: String, bitmap: Bitmap) {
    if (getCachedBitmap(path) == null) {
        coverBitmapCache.put(path, bitmap)
    }
}

private fun loadBitmap(
    context: android.content.Context,
    coverPath: String?,
    reqWidth: Int = DEFAULT_COVER_DECODE_WIDTH,
    reqHeight: Int = DEFAULT_COVER_DECODE_HEIGHT
): Bitmap? {
    if (coverPath.isNullOrBlank()) return null

    return runCatching {
        fun openStream() = when {
            coverPath.startsWith("content://") -> {
                context.contentResolver.openInputStream(coverPath.toUri())
            }
            coverPath.startsWith("http://") || coverPath.startsWith("https://") -> {
                val cachedFile = getOrCreateRemoteImageCacheFile(context, coverPath)
                if (cachedFile != null && cachedFile.exists()) {
                    cachedFile.inputStream()
                } else {
                    val connection = URL(coverPath).openConnection() as HttpURLConnection
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 12_000
                    connection.instanceFollowRedirects = true
                    connection.inputStream
                }
            }
            else -> {
                val file = File(coverPath)
                if (file.exists()) file.inputStream() else null
            }
        }

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream()?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }

        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var inSampleSize = 1
        val height = options.outHeight
        val width = options.outWidth

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            this.inJustDecodeBounds = false
            this.inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        openStream()?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        }
    }.getOrElse { e ->
        Log.w("GameCoverArt", "Failed to load cover from $coverPath: ${e.message}")
        null
    }
}

private fun getOrCreateRemoteImageCacheFile(context: android.content.Context, url: String): File? {
    val cacheDir = File(context.cacheDir, "remote-image-cache").apply { mkdirs() }
    pruneRemoteImageCacheOnce(cacheDir)
    val missFile = File(cacheDir, "${url.sha1()}.miss")
    if (missFile.exists()) {
        if (System.currentTimeMillis() - missFile.lastModified() < REMOTE_MISS_TTL_MS) return null
        missFile.delete()
    }
    var receivedNotFound = false
    for (candidateUrl in remoteImageCandidates(url)) {
        val extension = candidateUrl.substringAfterLast('.', "").substringBefore('?').lowercase()
            .takeIf { it in setOf("jpg", "jpeg", "png", "webp") }
            ?: "img"
        val targetFile = File(cacheDir, "${candidateUrl.sha1()}.$extension")
        if (isDecodableCachedImage(targetFile)) {
            return targetFile
        }
        if (targetFile.exists()) targetFile.delete()

        val tempFile = File.createTempFile("${targetFile.name}.", ".tmp", cacheDir)
        val downloaded = runCatching {
            val connection = URL(candidateUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 12_000
                connection.instanceFollowRedirects = true
                if (connection.responseCode !in 200..299) {
                    if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) receivedNotFound = true
                    return@runCatching null
                }
                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } finally {
                connection.disconnect()
            }

            if (!isDecodableCachedImage(tempFile)) return@runCatching null
            synchronized(remoteCacheWriteLock) {
                if (isDecodableCachedImage(targetFile)) {
                    return@synchronized targetFile
                }
                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) tempFile.copyTo(targetFile, overwrite = true)
                targetFile.takeIf(::isDecodableCachedImage)
            }
        }.getOrNull()

        if (downloaded != null) {
            missFile.delete()
            return downloaded
        }
        tempFile.delete()
    }
    if (receivedNotFound) {
        runCatching { missFile.writeText(System.currentTimeMillis().toString()) }
    }
    return null
}

private const val REMOTE_MISS_TTL_MS = 7L * 24L * 60L * 60L * 1000L
private const val REMOTE_CACHE_MAX_BYTES = 256L * 1024L * 1024L
private val remoteCachePruned = AtomicBoolean(false)
private val remoteCacheWriteLock = Any()

private fun isDecodableCachedImage(file: File): Boolean {
    if (!file.isFile || file.length() <= 0L) return false
    return runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        options.outWidth > 0 && options.outHeight > 0
    }.getOrDefault(false)
}

private fun pruneRemoteImageCacheOnce(cacheDir: File) {
    if (!remoteCachePruned.compareAndSet(false, true)) return
    val cachedImages = cacheDir.listFiles()
        .orEmpty()
        .filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".miss") }
        .sortedBy { it.lastModified() }
    var totalBytes = cachedImages.sumOf { it.length() }
    for (file in cachedImages) {
        if (totalBytes <= REMOTE_CACHE_MAX_BYTES) break
        val size = file.length()
        if (file.delete()) totalBytes -= size
    }
    cacheDir.listFiles()
        .orEmpty()
        .filter { it.name.endsWith(".tmp") && System.currentTimeMillis() - it.lastModified() > 60L * 60L * 1000L }
        .forEach { it.delete() }
}

private fun remoteImageCandidates(url: String): List<String> {
    val withoutQuery = url.substringBefore('?')
    return when {
        withoutQuery.endsWith(".jpg", ignoreCase = true) -> listOf(url, url.replaceSuffixPreservingQuery(".jpg", ".png"))
        withoutQuery.endsWith(".jpeg", ignoreCase = true) -> listOf(url, url.replaceSuffixPreservingQuery(".jpeg", ".png"))
        withoutQuery.endsWith(".png", ignoreCase = true) -> listOf(url, url.replaceSuffixPreservingQuery(".png", ".jpg"))
        else -> listOf(url)
    }.distinct()
}

private fun String.replaceSuffixPreservingQuery(oldSuffix: String, newSuffix: String): String {
    val query = substringAfter('?', missingDelimiterValue = "")
    val base = substringBefore('?')
    val replacedBase = base.removeSuffix(oldSuffix).removeSuffix(oldSuffix.uppercase()) + newSuffix
    return if (query.isBlank()) replacedBase else "$replacedBase?$query"
}

private fun String.sha1(): String {
    val digest = MessageDigest.getInstance("SHA-1")
    return digest.digest(toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Removes the transparent export canvas from Sega cases without cropping the artwork. */
private fun trimCoverTransparency(bitmap: Bitmap): Bitmap {
    if (!bitmap.hasAlpha()) return bitmap
    val row = IntArray(bitmap.width)
    var left = bitmap.width
    var right = -1
    var top = bitmap.height
    var bottom = -1
    for (y in 0 until bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        for (x in row.indices) {
            if ((row[x] ushr 24) > 8) {
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
            }
        }
    }
    if (right < left || bottom < top) return bitmap
    return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
}
