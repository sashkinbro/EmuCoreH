package com.sbro.emucoreh.data.catalog

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
}
