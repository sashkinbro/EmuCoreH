package com.sbro.emucoreh.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class RateLimitException(val resetTimestampMs: Long) : IOException("Rate limit exceeded")

data class AppUpdateRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val publishedAt: String,
    val htmlUrl: String,
    val apkAssetName: String?,
    val apkDownloadUrl: String?,
    val apkSizeBytes: Long?,
    val parallelApkAssetName: String? = null,
    val parallelApkDownloadUrl: String? = null,
    val parallelApkSizeBytes: Long? = null
) {
    val displayName: String = name.ifBlank { tagName }
    val hasParallelApk: Boolean = !parallelApkDownloadUrl.isNullOrBlank()
}

class AppUpdateRepository(private val context: Context) {

    fun loadReleaseHistory(force: Boolean = false): List<AppUpdateRelease> {
        val cacheFile = File(context.cacheDir, "release_history.json")
        if (!force && isCacheValid(cacheFile)) {
            val cachedJson = runCatching { cacheFile.readText() }.getOrNull()
            if (cachedJson != null) {
                return parseReleaseList(cachedJson)
            }
        }

        if (!hasNetwork()) {
            val cachedJson = runCatching { cacheFile.readText() }.getOrNull()
            if (cachedJson != null) {
                return parseReleaseList(cachedJson)
            }
            return emptyList()
        }

        val connection = openConnection()
        val etagFile = File(context.cacheDir, "release_history.etag")
        if (etagFile.exists() && cacheFile.exists()) {
            val etag = runCatching { etagFile.readText() }.getOrNull()
            if (!etag.isNullOrBlank()) {
                connection.setRequestProperty("If-None-Match", etag)
            }
        }

        return try {
            ensureSuccess(connection)
            if (connection.responseCode == 304) {
                val json = cacheFile.readText()
                return parseReleaseList(json)
            }
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            cacheFile.writeText(json)
            connection.getHeaderField("ETag")?.let { etagFile.writeText(it) }
            parseReleaseList(json)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseReleaseList(json: String): List<AppUpdateRelease> {
        val items = JSONArray(json)
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                add(parseRelease(item))
            }
        }
    }

    private fun parseRelease(root: JSONObject): AppUpdateRelease {
        val assets = root.optJSONArray("assets")
        var apkAssetName: String? = null
        var apkDownloadUrl: String? = null
        var apkSizeBytes: Long? = null
        var parallelApkAssetName: String? = null
        var parallelApkDownloadUrl: String? = null
        var parallelApkSizeBytes: Long? = null
        if (assets != null) {
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val downloadUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                val sizeBytes = asset.optLong("size").takeIf { it > 0L }
                if (name.contains("parallel", ignoreCase = true)) {
                    if (parallelApkDownloadUrl == null) {
                        parallelApkAssetName = name
                        parallelApkDownloadUrl = downloadUrl
                        parallelApkSizeBytes = sizeBytes
                    }
                } else if (apkDownloadUrl == null) {
                    apkAssetName = name
                    apkDownloadUrl = downloadUrl
                    apkSizeBytes = sizeBytes
                }
            }
        }
        return AppUpdateRelease(
            tagName = root.optString("tag_name"),
            name = root.optString("name"),
            body = root.optString("body"),
            publishedAt = root.optString("published_at"),
            htmlUrl = root.optString("html_url"),
            apkAssetName = apkAssetName,
            apkDownloadUrl = apkDownloadUrl,
            apkSizeBytes = apkSizeBytes,
            parallelApkAssetName = parallelApkAssetName,
            parallelApkDownloadUrl = parallelApkDownloadUrl,
            parallelApkSizeBytes = parallelApkSizeBytes
        )
    }

    private fun hasNetwork(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun currentVersionName(): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0.0.0"
    }

    private fun openConnection(): HttpURLConnection {
        return (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json,application/json,*/*")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "EmuCoreH/${currentVersionName()}")
        }
    }

    private fun isCacheValid(file: File): Boolean {
        if (!file.exists()) return false
        val age = System.currentTimeMillis() - file.lastModified()
        return age < 15 * 60 * 1000L
    }

    private fun ensureSuccess(connection: HttpURLConnection) {
        val remaining = connection.getHeaderField("x-ratelimit-remaining")?.toIntOrNull() ?: -1
        val reset = connection.getHeaderField("x-ratelimit-reset")?.toLongOrNull() ?: 0L
        val responseCode = connection.responseCode

        if (responseCode == 403 && remaining == 0) {
            throw RateLimitException(reset * 1000L)
        }

        if (responseCode == 304) {
            return
        }

        if (responseCode !in 200..299) {
            val responseMessage = connection.responseMessage.orEmpty().ifBlank { "HTTP $responseCode" }
            throw IOException("Could not load release history: $responseMessage")
        }
    }

    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/sashkinbro/EmuCoreH/releases?per_page=100"
    }
}
