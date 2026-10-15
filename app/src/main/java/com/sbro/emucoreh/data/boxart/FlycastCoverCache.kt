package com.sbro.emucoreh.data.boxart

import java.io.File

internal class FlycastCoverCache(private val directory: File, private val usable: (File) -> Boolean) {
    private val extensions = listOf("png", "jpg", "jpeg", "webp")

    // Disc artwork has its own provenance, independent of online style/theme markers.
    fun offlineFile(key: String) = File(directory, "$key.disc.png")

    fun storeOffline(key: String, extract: (File) -> Unit): File? {
        val target = offlineFile(key)
        val temp = File.createTempFile("${key.take(40)}.disc.", ".tmp", directory)
        try {
            extract(temp)
            if (!usable(temp)) return target.takeIf(usable)
            if (!temp.renameTo(target)) temp.copyTo(target, overwrite = true)
            return target.takeIf(usable)
        } finally {
            temp.delete()
        }
    }

    fun onlineFile(key: String, extension: String, isLight: Boolean? = null): File {
        return if (extension == "webp" && isLight != null) {
            val themeTag = if (isLight) "light" else "dark"
            File(directory, "$key.$themeTag.online.$extension")
        } else {
            File(directory, "$key.online.$extension")
        }
    }

    // Retry misses recorded before the catalog and title matching corrections.
    fun missFile(key: String) = File(directory, "$key.miss-v5")

    fun isOnline(file: File): Boolean {
        if (file.name.endsWith(".disc.png")) return false
        if (file.name.contains(".online.")) return true
        val source = sourceUrl(file)
        // Legacy disc PNGs can coexist with a newer 3D cover's source marker.
        if (file.extension.equals("png", ignoreCase = true) && source.contains("/3d/")) return false
        return source.startsWith("https://") || source.startsWith("http://")
    }

    fun find(keys: List<String>, style: Int? = null, isLight: Boolean = false): File? {
        if (style == 0) return null // COVER_ART_STYLE_DISABLED
        val requested = keys.flatMap { key ->
            when (style) {
                2 -> {
                    val theme = if (isLight) "light" else "dark"
                    listOf(onlineFile(key, "webp", isLight), File(directory, "$key.$theme.webp"),
                        File(directory, "${key}_$theme.webp"), onlineFile(key, "webp"), File(directory, "$key.webp"))
                }
                1 -> listOf("jpg", "jpeg", "png").flatMap { extension ->
                    listOf(onlineFile(key, extension), File(directory, "$key.$extension"))
                }
                else -> extensions.flatMap { extension ->
                    listOf(onlineFile(key, extension), File(directory, "$key.$extension"))
                }
            }
        }.filter(usable).filter { matchesStyle(it, style, isLight) }
        requested.firstOrNull(::isOnline)?.let { return it }
        requested.firstOrNull()?.let { return it }
        // A selected online style must never hide locally extracted disc artwork.
        // Keep legacy disc paths usable as well, without showing the wrong online style.
        return keys.asSequence().flatMap { key ->
            sequenceOf(offlineFile(key), File(directory, "$key.png"))
        }.firstOrNull { usable(it) && !isOnline(it) }
    }

    private fun matchesStyle(file: File, style: Int?, isLight: Boolean): Boolean {
        if (style == null) return true
        val source = sourceUrl(file)
        // Modern online filenames encode their format. A generic source marker
        // belongs to the last download and may describe another cached style.
        val is3d = file.extension.equals("webp", ignoreCase = true) ||
            (!file.name.contains(".online.") && source.contains("/3d/"))
        if (style == 1) return !is3d
        if (style != 2 || !is3d) return false
        val light = when {
            file.name.contains(".light.") || file.name.contains("_light.") -> true
            file.name.contains(".dark.") || file.name.contains("_dark.") -> false
            else -> source.contains("/3d/light/") || source.contains("_light.")
        }
        return light == isLight
    }

    private fun sourceUrl(file: File): String {
        val name = file.nameWithoutExtension.removeSuffix(".online")
        val theme = when {
            name.endsWith(".light") -> "light"
            name.endsWith(".dark") -> "dark"
            else -> null
        }
        val key = name.removeSuffix(".light").removeSuffix(".dark")
        val sources = if (theme == null) listOf(File(directory, "$key.url"))
            else listOf(File(directory, "$key.$theme.url"), File(directory, "$key.url"))
        return runCatching { sources.firstOrNull { it.isFile }?.readText()?.trim() }.getOrNull().orEmpty()
    }
}
