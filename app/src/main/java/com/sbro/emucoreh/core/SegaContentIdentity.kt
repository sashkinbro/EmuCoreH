package com.sbro.emucoreh.core

import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale

/** Uses the user's source identity, never the transient materialized filename. */
internal object SegaContentIdentity {
    private val discMarker = Regex("""[\s_-]*(?:\((?:disc|disk|cd)\s*\d+(?:\s*of\s*\d+)?\)|\[(?:disc|disk|cd)[\s_-]*\d+]|(?:disc|disk|cd)[\s_-]+\d+)""", RegexOption.IGNORE_CASE)

    fun batteryKey(originalPath: String, displayName: String = originalPath.substringAfterLast('/')): String {
        val decoded = if (originalPath.startsWith("content://"))
            runCatching { URLDecoder.decode(originalPath.replace("+", "%2B"), "UTF-8") }.getOrDefault(originalPath)
        else originalPath
        val parent = decoded.substringBeforeLast('/', "")
        val name = displayName.substringBeforeLast('.').replace(discMarker, "").trim().lowercase(Locale.ROOT)
        val digest = MessageDigest.getInstance("SHA-256").digest("$parent/$name".toByteArray())
        return "game-" + digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
