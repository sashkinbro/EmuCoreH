package com.sbro.emucoreh.data

import com.sbro.emucoreh.data.catalog.ArcadeCatalogRepository
import com.sbro.emucoreh.core.SegaPlatformDetector
import java.util.Locale

enum class LibraryPlatform {
    DREAMCAST,
    NAOMI,
    NAOMI_2,
    ATOMISWAVE,
    SYSTEM_SP,
    MODEL_3,
    GENESIS,
    SEGA_CD,
    SEGA_32X,
    MASTER_SYSTEM,
    GAME_GEAR,
    SG_1000,
    ARCADE,
}

object LibraryPlatformResolver {
    private val arcadeExtensions = setOf("zip", "7z", "dat", "lst", "bin")
    private const val MAX_PARENT_DEPTH = 16

    fun resolve(
        game: GameItem,
        findArcade: (String) -> ArcadeCatalogRepository.ArcadeGame?,
    ): LibraryPlatform {
        game.platform?.let { platform ->
            val normalized = platform.uppercase(Locale.ROOT)
            if (normalized in setOf("MODEL3", "MODEL 3", "SEGA MODEL 3")) return LibraryPlatform.MODEL_3
            entriesByName[normalized]?.let { return it }
        }
        val sega = SegaPlatformDetector.detectName(game.fileName)
        if (sega.isSega || sega.isModel3) return entriesByName.getValue(sega.name)
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

    private val entriesByName = LibraryPlatform.entries.associateBy { it.name }
}
