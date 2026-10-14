/*
	Copyright 2022 flyinghead

	This file is part of Flycast.

    Flycast is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.

    Flycast is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Flycast.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sbro.emucoreh.data.boxart

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Kotlin port of Flycast's TheGamesDb box art scraper
 * (core/core/ui/boxart/gamesdb.cpp). Games are matched by product code first
 * and by name second, and the artwork is downloaded from TheGamesDB CDN using
 * the same public API key as the official emulator.
 */
internal class TheGamesDbClient(private val baseUrl: String = "https://api.thegamesdb.net/v1/") {

    companion object {
        private const val API_KEY = "3fcc5e726a129924972be97abfd577ac5311f8f12398a9d9bcb5a377d4656fa8"
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 15000
    }

    data class GameInfo(
        val title: String?,
        val overview: String?,
        val releaseDate: String?,
        val imageUrl: String?
    )

    @Volatile
    private var dreamcastPlatformId = 0

    @Volatile
    private var arcadePlatformId = 0

    @Volatile
    private var blackoutUntil = 0L

    fun findByUniqueId(uniqueId: String): GameInfo? {
        if (uniqueId.isBlank()) return null
        val platform = fetchPlatforms() ?: return null
        if (platform.dreamcast == 0) return null
        val url = makeUrl("Games/ByGameUniqueID") +
            "&fields=overview,uids&include=boxart&filter%5Bplatform%5D=" +
            platform.dreamcast + "&uid=" + urlEncode(uniqueId)
        return fetchGameInfo(url, uniqueId)
    }

    fun findByUniqueIds(uniqueIds: List<String>): Map<String, GameInfo?> {
        val ids = uniqueIds.filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return emptyMap()
        val platform = fetchPlatforms() ?: return emptyMap()
        val results = LinkedHashMap<String, GameInfo?>()
        // Flycast processes ten discs per online scrape. Keep that bound and
        // match each response by its UID, never by the result ordering.
        for (batch in ids.chunked(10)) {
            val url = makeUrl("Games/ByGameUniqueID") +
                "&fields=overview,uids&include=boxart&filter%5Bplatform%5D=" +
                platform.dreamcast + "&uid=" + urlEncode(batch.joinToString(","))
            val response = httpGet(url)
            for (id in batch) results[id] = response?.let { parseGameInfo(it, id) }
        }
        return results
    }

    fun findByName(name: String, arcade: Boolean = false): GameInfo? {
        if (name.isBlank()) return null
        val platform = fetchPlatforms() ?: return null
        val platformId = if (arcade) platform.arcade else platform.dreamcast
        if (platformId == 0) return null
        val url = makeUrl("Games/ByGameName") + "&fields=overview&include=boxart&filter%5Bplatform%5D=" +
            platformId + "&name=" + urlEncode(name)
        return fetchGameInfo(url)
    }

    private data class Platforms(val dreamcast: Int, val arcade: Int)

    @Synchronized
    private fun fetchPlatforms(): Platforms? {
        if (dreamcastPlatformId != 0 && arcadePlatformId != 0) {
            return Platforms(dreamcastPlatformId, arcadePlatformId)
        }
        var dreamcast = 0
        var arcade = 0
        val getPlatformId = platform@{ platform: String ->
            val url = makeUrl("Platforms/ByPlatformName") + "&name=" + platform
            val response = httpGet(url) ?: return@platform
            val array = response.optJSONObject("data")?.optJSONArray("platforms") ?: return@platform
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                when (entry.optString("name")) {
                    "Sega Dreamcast" -> dreamcast = entry.optInt("id")
                    "Arcade" -> arcade = entry.optInt("id")
                }
            }
        }
        getPlatformId("Dreamcast")
        getPlatformId("Arcade")
        if (dreamcast == 0 || arcade == 0) retry("Missing platform identifiers")
        dreamcastPlatformId = dreamcast
        arcadePlatformId = arcade
        return Platforms(dreamcast, arcade)
    }

    private fun fetchGameInfo(url: String, uniqueId: String = ""): GameInfo? {
        val response = httpGet(url) ?: return null
        return parseGameInfo(response, uniqueId)
    }

    private fun parseGameInfo(response: JSONObject, uniqueId: String = ""): GameInfo? {
        val games = gameEntries(response)
        val boxart = runCatching { response.getJSONObject("include").optJSONObject("boxart") }
            .getOrNull()
        for (game in games) {
            if (uniqueId.isNotEmpty() && !gameHasUniqueId(game, uniqueId)) continue
            var imageUrl = parseBoxart(boxart, game.optInt("id"))
            if (imageUrl == null) {
                val gameId = game.optInt("id")
                if (gameId != 0) {
                    val imagesUrl = makeUrl("Games/Images") + "&games_id=" + gameId
                    imageUrl = parseBoxart(httpGet(imagesUrl)?.optJSONObject("data"), gameId)
                }
            }
            return GameInfo(
                title = game.optString("game_title").takeIf { it.isNotBlank() },
                overview = game.optString("overview").takeIf { it.isNotBlank() },
                releaseDate = game.optString("release_date").takeIf { it.isNotBlank() },
                imageUrl = imageUrl
            )
        }
        return null
    }

    /**
     * TheGamesDB returns the "games" collection either as an array or as an
     * object keyed by game id, depending on the endpoint.
     */
    private fun gameEntries(response: JSONObject): List<JSONObject> {
        val data = response.optJSONObject("data") ?: retry("Missing games response data")
        val games = data.opt("games")
        return when {
            games is JSONArray -> (0 until games.length()).mapNotNull { games.optJSONObject(it) }
            games is JSONObject -> games.keys().asSequence().mapNotNull { games.optJSONObject(it) }.toList()
            else -> retry("Missing games collection")
        }
    }

    private fun gameHasUniqueId(game: JSONObject, uniqueId: String): Boolean {
        val uids = game.optJSONArray("uids") ?: return false
        for (index in 0 until uids.length()) {
            val entry = uids.optJSONObject(index) ?: continue
            if (entry.optString("uid").equals(uniqueId, ignoreCase = true)) return true
        }
        return false
    }

    private fun parseBoxart(boxartElement: JSONObject?, gameId: Int): String? {
        if (boxartElement == null || gameId == 0) return null
        val baseUrl = boxartElement.optJSONObject("base_url")?.let { urls ->
            (urls.opt("thumb") as? String).orEmpty().ifBlank { (urls.opt("small") as? String).orEmpty() }
        }?.takeIf { it.isNotBlank() } ?: return null

        val images = when {
            boxartElement.has("data") -> boxartElement.optJSONObject("data")
            boxartElement.has("images") -> boxartElement.optJSONObject("images")
            else -> null
        } ?: return null

        val dataArray = images.optJSONArray(gameId.toString()) ?: return null
        val imagePath = firstImageFilename(dataArray, "boxart")
            ?: firstImageFilename(dataArray, "titlescreen")
            ?: firstImageFilename(dataArray, "screenshot")
            ?: return null
        return baseUrl + imagePath
    }

    private fun firstImageFilename(images: JSONArray, type: String): String? {
        for (index in 0 until images.length()) {
            val image = images.optJSONObject(index) ?: continue
            if (!image.optString("type").equals(type, ignoreCase = true)) continue
            if (type == "boxart" && image.optString("side").equals("back", ignoreCase = true)) continue
            val filename = image.opt("filename") as? String ?: continue
            if (filename.isNotBlank()) return filename
        }
        return null
    }

    private fun makeUrl(endpoint: String): String = "$baseUrl$endpoint?apikey=$API_KEY"

    private fun httpGet(url: String): JSONObject? {
        if (System.currentTimeMillis() < blackoutUntil) throw BoxartRetryException(blackoutUntil)
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
            ?: retry("Cannot open API connection")
        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (EmuCoreH)")
            connection.connect()
            val status = connection.responseCode
            if (status == 403 || status == 429) {
                retry("TheGamesDB allowance reached", 24 * 60 * 60 * 1000L)
            }
            // The API uses 404 for an empty search. It must not black out
            // the name fallback or concurrent lookups for unrelated games.
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (status !in 200..299) {
                retry("TheGamesDB HTTP $status")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) retry("Empty API response")
            val response = JSONObject(body)
            val code = response.optInt("code", 200)
            if (code == 404) return null
            if (code == 403 || code == 429) retry("TheGamesDB allowance reached", 24 * 60 * 60 * 1000L)
            if (code !in 200..299) {
                retry("TheGamesDB error $code")
            }
            response
        } catch (error: BoxartRetryException) {
            throw error
        } catch (error: Exception) {
            retry("TheGamesDB request failed: ${error.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun retry(message: String, delayMillis: Long = 1000L): Nothing {
        synchronized(this) {
            blackoutUntil = maxOf(blackoutUntil, System.currentTimeMillis() + delayMillis)
        }
        throw BoxartRetryException(blackoutUntil, message)
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

internal object TheGamesDb {
    private val client = TheGamesDbClient()
    fun findByUniqueId(uniqueId: String) = client.findByUniqueId(uniqueId)
    fun findByName(name: String, arcade: Boolean = false) = client.findByName(name, arcade)
    fun findByUniqueIds(uniqueIds: List<String>) = client.findByUniqueIds(uniqueIds)
}

internal class BoxartRetryException(val retryAtMillis: Long, message: String = "Online artwork lookup paused") :
    RuntimeException(message)
