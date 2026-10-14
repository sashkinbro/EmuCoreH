package com.sbro.emucoreh.data.boxart

import java.io.File

internal class FlycastCoverCache(private val directory: File, private val usable: (File) -> Boolean) {
    private val extensions = listOf("png", "jpg", "jpeg", "webp")
    fun onlineFile(key: String, extension: String) = File(directory, "$key.online.$extension")
    // Old markers also represented network/download failures. Ignore them
    // after upgrading, so users do not need to clear their covers manually.
    fun missFile(key: String) = File(directory, "$key.miss-v2")
    fun clearOnlineSource(key: String) { File(directory, "$key.url").delete() }
    fun isOnline(file: File): Boolean {
        if (file.nameWithoutExtension.endsWith(".online")) return true
        val key = file.nameWithoutExtension.removeSuffix(".online")
        val source = runCatching { File(directory, "$key.url").readText().trim() }.getOrDefault("")
        return source.startsWith("https://") || source.startsWith("http://")
    }
    fun find(keys: List<String>): File? {
        val candidates = keys.flatMap { key ->
            extensions.map { onlineFile(key, it) } + extensions.map { File(directory, "$key.$it") }
        }.filter(usable)
        return candidates.firstOrNull(::isOnline) ?: candidates.firstOrNull()
    }
}
