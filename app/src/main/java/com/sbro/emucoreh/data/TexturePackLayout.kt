package com.sbro.emucoreh.data

/**
 * Paths have already passed the ZIP traversal check.
 *
 * PSP packs carry a single textures.ini and stay relative to it. Dreamcast
 * packs ship as <game id>/<hash>.png with no INI, so the game folder is
 * dropped here and re-added from the serial when the pack is staged.
 */
internal fun resolveTexturePackLayout(paths: Set<String>): Map<String, String> {
    val mainIni = paths.filter { it.substringAfterLast('/').equals("textures.ini", ignoreCase = true) }
        .singleOrNull()
    if (mainIni != null) {
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

    val roots = paths.map { it.substringBefore('/') }.toSet()
    require(roots.size == 1) { "Expected one game folder per texture pack" }
    val root = roots.single() + "/"
    val destinations = hashSetOf<String>()
    return buildMap {
        paths.forEach { source ->
            require(source.startsWith(root)) { "Invalid texture pack path" }
            val relative = source.removePrefix(root)
            require(relative.isNotEmpty()) { "Invalid texture pack path" }
            require(destinations.add(relative)) { "Duplicate texture pack path" }
            put(source, relative)
        }
    }
}
