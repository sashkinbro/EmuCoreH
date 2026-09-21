package com.sbro.emucoreh.core

import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

/** Result of a native RetroAchievements HTTP request. */
class AchievementsHttpResponse(
    @JvmField val statusCode: Int,
    @JvmField val body: ByteArray
)

/**
 * Synchronous HTTP transport for rcheevos' server calls. The native client
 * calls this from its HTTP worker thread; the credentials are posted over TLS
 * to the RetroAchievements API exactly as the library formats them.
 */
object AchievementsHttp {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    @JvmStatic
    fun request(url: String, postData: String?, contentType: String?): AchievementsHttpResponse {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = if (postData != null) "POST" else "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "EmuCoreH")
                setRequestProperty("Accept", "application/json")
                if (postData != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType ?: "application/x-www-form-urlencoded")
                }
            }

            if (postData != null) {
                connection.outputStream.use { output ->
                    output.write(postData.toByteArray(Charsets.UTF_8))
                }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.let { input ->
                BufferedInputStream(input).use { it.readBytes() }
            } ?: ByteArray(0)
            AchievementsHttpResponse(status, body)
        } catch (_: Exception) {
            AchievementsHttpResponse(-1, ByteArray(0))
        } finally {
            connection?.disconnect()
        }
    }
}
