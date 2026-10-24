package com.sbro.emucoreh.data.catalog

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArcadeCatalogTest {

    @Test
    fun extractsTheRomsetFromArchivesAndPaths() {
        assertEquals("mslug6", ArcadeCatalogRepository.romsetKey("mslug6.zip"))
        assertEquals("naomi2", ArcadeCatalogRepository.romsetKey("NAOMI2.ZIP"))
        assertEquals("ikaruga", ArcadeCatalogRepository.romsetKey("ikaruga.7z"))
        assertEquals("bdrdown", ArcadeCatalogRepository.romsetKey("18wheelr/bdrdown.zip"))
        assertEquals("cvs2gd", ArcadeCatalogRepository.romsetKey("roms\\naomi\\cvs2gd.7z"))
        assertEquals("initdv2j", ArcadeCatalogRepository.romsetKey("initdv2j"))
    }

    @Test
    fun rejectsUnusableNames() {
        assertNull(ArcadeCatalogRepository.romsetKey(null))
        assertNull(ArcadeCatalogRepository.romsetKey(""))
        assertNull(ArcadeCatalogRepository.romsetKey("   "))
        assertNull(ArcadeCatalogRepository.romsetKey("folder/"))
    }

    @Test
    fun uniqueArcadeTitlesResolveTheirRomsetForConvertedDiscArtwork() {
        val asset = listOf(
            File("src/main/assets/catalog/arcade_games.json"),
            File("app/src/main/assets/catalog/arcade_games.json"),
        ).first { it.isFile }
        val games = JSONObject(asset.readText()).getJSONObject("games")
        val titles = ArcadeCatalogRepository.romsetTitles(games)

        assertEquals("mslug6", titles["metal slug 6"])
        assertEquals("kofxi", titles["the king of fighters xi"])
        assertEquals("kofnw", titles["the king of fighters neowave"])
        assertNull(titles["not a real arcade game"])
    }
}
