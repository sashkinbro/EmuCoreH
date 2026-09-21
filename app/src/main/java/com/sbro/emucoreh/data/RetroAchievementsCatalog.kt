package com.sbro.emucoreh.data

import java.util.Locale
import org.json.JSONObject

internal object RetroAchievementsCatalog {
    /** RetroAchievements console id for the Dreamcast. */
    const val DREAMCAST_CONSOLE_ID = 40

    fun titleKeys(value: String): Set<String> {
        val name = value.replace(Regex("^(disney[ /-]*pixar|disney(?:’s|'s)?)[ :/-]+", RegexOption.IGNORE_CASE), "")
        if (name.contains("[Subset", ignoreCase = true)) return setOf(titleKey(name))
        return setOf(titleKey(name), titleKey(name.substringBefore(":")))
    }

    fun titleKey(value: String): String {
        var name = value.trim().lowercase(Locale.ROOT)
        val extension = Regex("\\.(gdi|cue|chd|cdi|iso|dat|m3u|lst|elf|bin|zip|7z)$")
        if (extension.containsMatchIn(name)) {
            name = name.replace(extension, "")
                .replace(Regex("(\\s*\\([^)]*\\)|\\s*\\[[^]]*\\])+$"), "")
        }
        return name.replace(Regex("[^a-z0-9]+"), "")
    }

    fun String.parseAccountProgress(): Map<Int, AccountProgressEntry> {
        val root = JSONObject(this)
        check(root.optBoolean("Success")) { "RetroAchievements request failed" }
        val response = root.optJSONObject("Response") ?: run {
            check(root.optJSONArray("Response")?.length() == 0) { "Invalid achievement catalog" }
            return emptyMap()
        }
        return buildMap {
            response.keys().forEach { key ->
                val gameId = key.toIntOrNull() ?: return@forEach
                val item = response.optJSONObject(key) ?: return@forEach
                val total = item.optInt("Achievements").coerceAtLeast(0)
                if (total <= 0) return@forEach
                put(
                    gameId,
                    AccountProgressEntry(
                        gameId = gameId,
                        earned = item.optInt("Unlocked").coerceIn(0, total),
                        hardcore = item.optInt("UnlockedHardcore").coerceAtLeast(0),
                        total = total
                    )
                )
            }
        }
    }

    fun String.parseGameTitles(): Map<Int, Pair<String, String>> {
        val root = JSONObject(this)
        check(root.optBoolean("Success")) { "RetroAchievements request failed" }
        return buildMap {
            root.optJSONArray("Response")?.let { response ->
                for (index in 0 until response.length()) {
                    val item = response.optJSONObject(index) ?: continue
                    putRemoteGameTitle(item.optInt("ID"), item)
                }
            } ?: root.optJSONObject("Response")?.let { response ->
                response.keys().forEach { key ->
                    val item = response.optJSONObject(key) ?: return@forEach
                    putRemoteGameTitle(key.toIntOrNull() ?: item.optInt("ID"), item)
                }
            }
        }
    }

    private fun MutableMap<Int, Pair<String, String>>.putRemoteGameTitle(id: Int, item: JSONObject) {
        if (id <= 0 || containsKey(id)) return
        val image = item.optString("ImageIconURL").takeIf { it.isNotBlank() }
            ?: item.optString("ImageIcon").takeIf { it.isNotBlank() }
            ?: item.optString("ImageUrl").takeIf { it.isNotBlank() }
        put(id, item.optString("Title") to when {
            image.isNullOrBlank() -> ""
            image.startsWith("http") -> image
            image.startsWith("/media/") -> "https://retroachievements.org$image"
            else -> "https://media.retroachievements.org/${image.trimStart('/')}"
        })
    }

}
