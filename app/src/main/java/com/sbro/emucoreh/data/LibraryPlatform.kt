package com.sbro.emucoreh.data

import com.sbro.emucoreh.data.catalog.ArcadeCatalogRepository
import java.util.Locale

enum class LibraryPlatform {
    DREAMCAST,
    NAOMI,
    NAOMI_2,
    ATOMISWAVE,
    SYSTEM_SP,
    ARCADE,
}

object LibraryPlatformResolver {
    private val arcadeExtensions = setOf("zip", "7z", "dat", "lst", "bin")
    private const val MAX_PARENT_DEPTH = 16

    fun resolve(
        game: GameItem,
        findArcade: (String) -> ArcadeCatalogRepository.ArcadeGame?,
    ): LibraryPlatform {
        val extension = game.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        // Arcade GD-ROMs launch through their archives; a same-named disc is Dreamcast.
        if (extension !in arcadeExtensions) return LibraryPlatform.DREAMCAST

        var romset = ArcadeCatalogRepository.romsetKey(game.fileName) ?: return LibraryPlatform.ARCADE
        val visited = mutableSetOf<String>()
        repeat(MAX_PARENT_DEPTH) {
            if (!visited.add(romset)) return LibraryPlatform.ARCADE
            val entry = findArcade(romset) ?: return LibraryPlatform.ARCADE
            val bios = entry.bios?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            if (bios != null) {
                return when (bios) {
                    "naomi", "airlbios", "f355bios", "f355dlx", "hod2bios", "naomi-jp-d", "naomi-ko-h" ->
                        LibraryPlatform.NAOMI
                    "naomi2" -> LibraryPlatform.NAOMI_2
                    "awbios" -> LibraryPlatform.ATOMISWAVE
                    "segasp" -> LibraryPlatform.SYSTEM_SP
                    else -> LibraryPlatform.ARCADE
                }
            }
            romset = entry.parent?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
                ?: return LibraryPlatform.ARCADE
        }
        return LibraryPlatform.ARCADE
    }
}
