package com.sbro.emucoreh.data

/** Paths have already passed the ZIP traversal check. Keep assets relative to the main INI. */
internal fun resolveTexturePackLayout(paths: Set<String>): Map<String, String> {
    val mainIni = paths.filter { it.substringAfterLast('/').equals("textures.ini", ignoreCase = true) }
        .singleOrNull() ?: error("Expected one textures.ini per texture pack")
    val prefix = mainIni.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
    val destinations = hashSetOf<String>()
    return buildMap {
        paths.filter { it.startsWith(prefix) }.forEach { source ->
            val relative = if (source == mainIni) "textures.ini" else source.removePrefix(prefix)
            require(destinations.add(relative)) { "Duplicate texture pack path" }
            put(source, relative)
        }
    }
}
