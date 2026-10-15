package com.sbro.emucoreh.data

import com.sbro.emucoreh.data.catalog.ArcadeCatalogRepository
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPlatformTest {
    @Test
    fun knownRomsetsUseTheirBundledCatalogPlatform() {
        listOf(
            "ikaruga.zip" to LibraryPlatform.NAOMI,
            "beachspi.zip" to LibraryPlatform.NAOMI_2,
            "mslug6.zip" to LibraryPlatform.ATOMISWAVE,
            "bingogal.zip" to LibraryPlatform.SYSTEM_SP,
        ).forEach { (fileName, expected) ->
            assertEquals(fileName, expected, resolve(fileName))
        }
    }

    @Test
    fun specialNaomiBiosRemainInTheNaomiSection() {
        listOf("alpilot", "f355twin", "f355", "hotd2", "marstv", "mushik2k").forEach { romset ->
            assertEquals(romset, LibraryPlatform.NAOMI, resolve("$romset.zip"))
        }
    }

    @Test
    fun arcadeExtensionsAndRomsetNamesAreCaseInsensitive() {
        listOf("ZIP", "7Z", "DAT", "LST", "BIN").forEach { extension ->
            assertEquals(extension, LibraryPlatform.ATOMISWAVE, resolve("MSLUG6.$extension"))
        }
    }

    @Test
    fun discAndPlaylistNamesNeverBecomeArcadeBecauseOfRomsetCollisions() {
        listOf("CHD", "GDI", "CDI", "CUE", "ISO", "M3U").forEach { extension ->
            val result = LibraryPlatformResolver.resolve(game("ikaruga.$extension")) {
                error("Disc and playlist formats must not consult the arcade catalog")
            }
            assertEquals(extension, LibraryPlatform.DREAMCAST, result)
        }
    }

    @Test
    fun renamedArchivesCannotBorrowTheTitleOrDirectoryPlatform() {
        val renamed = game("My Metal Slug 6.zip").copy(
            title = "Metal Slug 6",
            path = "/Atomiswave/mslug6.zip",
        )
        assertEquals(LibraryPlatform.ARCADE, LibraryPlatformResolver.resolve(renamed, ::findArcade))
    }

    @Test
    fun displayTitleAndDocumentUriDoNotOverrideTheFileName() {
        val renamedTitle = game("beachspi.zip").copy(
            title = "A Dreamcast Game",
            path = "content://documents/tree/Dreamcast/document/unrelated.chd",
        )
        assertEquals(LibraryPlatform.NAOMI_2, LibraryPlatformResolver.resolve(renamedTitle, ::findArcade))
    }

    @Test
    fun unknownLegacyArcadeDumpsRemainGenericArcade() {
        listOf("zip", "7z", "dat", "lst", "bin").forEach { extension ->
            assertEquals(extension, LibraryPlatform.ARCADE, resolve("unrecognized.$extension"))
        }
    }

    @Test
    fun missingCloneBiosCanUseItsKnownParent() {
        val clone = catalog.getValue("18wheelro").copy(bios = null)
        val result = LibraryPlatformResolver.resolve(game("18wheelro.zip")) { romset ->
            if (romset == "18wheelro") clone else catalog[romset]
        }
        assertEquals(LibraryPlatform.NAOMI, result)
    }

    @Test
    fun unrecognizedBiosDoesNotGuessFromTheCartridgeOrTitle() {
        val unsupported = catalog.getValue("mslug6").copy(bios = "future-bios")
        val result = LibraryPlatformResolver.resolve(game("mslug6.zip")) { unsupported }
        assertEquals(LibraryPlatform.ARCADE, result)
    }

    @Test
    fun cyclicOrMissingParentsRemainGenericArcade() {
        val first = ArcadeCatalogRepository.ArcadeGame("first", "First", "second")
        val second = ArcadeCatalogRepository.ArcadeGame("second", "Second", "first")
        val cyclic = mapOf("first" to first, "second" to second)
        assertEquals(
            LibraryPlatform.ARCADE,
            LibraryPlatformResolver.resolve(game("first.zip"), cyclic::get),
        )
        assertEquals(
            LibraryPlatform.ARCADE,
            LibraryPlatformResolver.resolve(game("first.zip"), mapOf("first" to first)::get),
        )
    }

    private fun resolve(fileName: String) = LibraryPlatformResolver.resolve(game(fileName), ::findArcade)

    private fun game(fileName: String) = GameItem(fileName, "/Games/$fileName", fileName, 1, 1)

    private fun findArcade(romset: String) = catalog[romset]

    companion object {
        // Read the shipped data so BIOS changes in the core catalog also exercise this mapping.
        private val catalog: Map<String, ArcadeCatalogRepository.ArcadeGame> by lazy {
            val asset = listOf(
                File("src/main/assets/catalog/arcade_games.json"),
                File("app/src/main/assets/catalog/arcade_games.json"),
            ).first { it.isFile }
            val games = JSONObject(asset.readText()).getJSONObject("games")
            games.keys().asSequence().associateWith { romset ->
                val entry = games.getJSONObject(romset)
                ArcadeCatalogRepository.ArcadeGame(
                    romset = romset,
                    title = entry.getString("title"),
                    parent = entry.optionalString("parent"),
                    bios = entry.optionalString("bios"),
                    cartridge = entry.optionalString("cart"),
                    rotation = entry.optionalString("rotation"),
                    gdrom = entry.optionalString("gdrom"),
                )
            }
        }

        private fun JSONObject.optionalString(key: String) = optString(key).takeIf { it.isNotBlank() }
    }
}
