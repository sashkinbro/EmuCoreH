package com.sbro.emucoreh.data.boxart

import java.io.File

internal class FlycastCoverCache(private val directory: File, private val usable: (File) -> Boolean) {
    private val extensions = listOf("png", "jpg", "jpeg", "webp")

    fun onlineFile(key: String, extension: String, isLight: Boolean? = null): File {
        return if (extension == "webp" && isLight != null) {
            val themeTag = if (isLight) "light" else "dark"
            File(directory, "$key.$themeTag.online.$extension")
        } else {
            File(directory, "$key.online.$extension")
        }
    }

    // Old markers also represented network/download failures. Ignore them
    // after upgrading, so users do not need to clear their covers manually.
    fun missFile(key: String) = File(directory, "$key.miss-v2")
    fun clearOnlineSource(key: String) {
        File(directory, "$key.url").delete()
        File(directory, "$key.light.url").delete()
        File(directory, "$key.dark.url").delete()
    }

    fun isOnline(file: File): Boolean {
        if (file.name.contains(".online.")) return true
        val baseKey = file.nameWithoutExtension.removeSuffix(".online").removeSuffix(".light").removeSuffix(".dark")
        val source = runCatching {
            listOf(
                File(directory, "$baseKey.light.url"),
                File(directory, "$baseKey.dark.url"),
                File(directory, "$baseKey.url")
            ).firstOrNull { it.isFile }?.readText()?.trim()
        }.getOrNull().orEmpty()
        return source.startsWith("https://") || source.startsWith("http://")
    }

    fun find(keys: List<String>, style: Int? = null, isLight: Boolean = false): File? {
        val is3d = style == 2 // COVER_ART_STYLE_3D
        val isFlat = style == 1 // COVER_ART_STYLE_FLAT
        val allCandidates = keys.flatMap { key ->
            if (is3d) {
                if (isLight) {
                    listOf(
                        onlineFile(key, "webp", isLight = true),
                        File(directory, "$key.light.webp"),
                        File(directory, "${key}_light.webp"),
                        onlineFile(key, "webp", isLight = null),
                        File(directory, "$key.webp")
                    )
                } else {
                    listOf(
                        onlineFile(key, "webp", isLight = false),
                        File(directory, "$key.dark.webp"),
                        File(directory, "${key}_dark.webp"),
                        onlineFile(key, "webp", isLight = null),
                        File(directory, "$key.webp")
                    )
                }
            } else if (isFlat) {
                listOf("jpg", "jpeg", "png").flatMap { ext ->
                    listOf(onlineFile(key, ext), File(directory, "$key.$ext"))
                }
            } else {
                extensions.flatMap { ext ->
                    listOf(onlineFile(key, ext), File(directory, "$key.$ext"))
                }
            }
        }.filter(usable).filter { candidate ->
            if (style != null) {
                val candidateIsWebp = candidate.name.endsWith(".webp", ignoreCase = true)
                val baseKey = candidate.nameWithoutExtension.removeSuffix(".online").removeSuffix(".light").removeSuffix(".dark")
                val sourceUrl = runCatching {
                    listOf(
                        File(directory, "$baseKey.light.url"),
                        File(directory, "$baseKey.dark.url"),
                        File(directory, "$baseKey.url")
                    ).firstOrNull { it.isFile }?.readText()?.trim()
                }.getOrNull().orEmpty()
                val candidateUrlIs3d = sourceUrl.contains("/3d/") || candidateIsWebp
                val candidateUrlIsLight = candidate.name.contains(".light.") ||
                    candidate.name.contains("_light.") ||
                    sourceUrl.contains("/3d/light/") ||
                    sourceUrl.contains("_light.")

                if (is3d) {
                    if (!candidateUrlIs3d) return@filter false
                    if (isLight != candidateUrlIsLight) return@filter false
                } else if (isFlat) {
                    if (candidateUrlIs3d) return@filter false
                }
            }
            true
        }
        return allCandidates.firstOrNull(::isOnline) ?: allCandidates.firstOrNull()
    }
}
