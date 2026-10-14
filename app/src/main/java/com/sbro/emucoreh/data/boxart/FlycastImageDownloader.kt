package com.sbro.emucoreh.data.boxart

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal class FlycastImageDownloader(private val directory: File, private val usable: (File) -> Boolean) {
    private val cache = FlycastCoverCache(directory, usable)

    fun download(url: String, key: String): String? {
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrElse {
            retry("Cannot open artwork connection")
        }
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (EmuCoreH)")
            val status = connection.responseCode
            if (status == 404 || status == 410) return null
            if (status !in 200..299) retry("Artwork HTTP $status")
            if (!connection.contentType.orEmpty().startsWith("image/")) retry("Invalid artwork content type")
            if (connection.contentLength == 0 || connection.contentLength > 16 * 1024 * 1024) retry("Invalid artwork length")
            val extension = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
                .lowercase(Locale.ROOT).takeIf { it in listOf("png", "jpg", "jpeg", "webp") } ?: "jpg"
            val is3d = url.contains("/3d/") || extension == "webp"
            val isLight = url.contains("/3d/light/") || url.contains("_light.")
            val target = cache.onlineFile(key, extension, if (is3d) isLight else null)
            val temp = File.createTempFile("${key.take(40)}.", ".tmp", directory)
            try {
                connection.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        val bytes = ByteArray(8192)
                        var copied = 0L
                        while (true) {
                            val count = input.read(bytes)
                            if (count < 0) break
                            copied += count
                            require(copied <= 16 * 1024 * 1024) { "Cover exceeds size limit" }
                            output.write(bytes, 0, count)
                        }
                    }
                }
                if (!usable(temp)) retry("Invalid downloaded artwork")
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) temp.copyTo(target, overwrite = true)
                if (!usable(target)) {
                    target.delete()
                    retry("Cannot store downloaded artwork")
                }
                if (is3d) {
                    val urlFileName = if (isLight) "$key.light.url" else "$key.dark.url"
                    runCatching { File(directory, urlFileName).writeText(url) }
                    runCatching { File(directory, "$key.url").writeText(url) }
                } else {
                    for (suffix in listOf("png", "jpg", "jpeg")) {
                        File(directory, "$key.$suffix").delete()
                    }
                    runCatching { File(directory, "$key.url").writeText(url) }
                }
                cache.missFile(key).delete()
                target.absolutePath
            } finally {
                temp.delete()
            }
        } catch (failure: BoxartRetryException) {
            throw failure
        } catch (failure: Exception) {
            retry("Artwork download failed: ${failure.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun retry(message: String): Nothing =
        throw BoxartRetryException(System.currentTimeMillis() + 1000L, message)
}
