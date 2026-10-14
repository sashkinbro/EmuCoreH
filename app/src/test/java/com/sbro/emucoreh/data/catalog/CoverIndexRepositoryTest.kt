package com.sbro.emucoreh.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverIndexRepositoryTest {

    @Test
    fun normalizesSerialsCorrectly() {
        assertEquals("MK51037", CoverIndexRepository.normalizeSerial("MK-51037"))
        assertEquals("MK51037", CoverIndexRepository.normalizeSerial("mk-51037"))
        assertEquals("MK51037", CoverIndexRepository.normalizeSerial("MK51037"))
        assertEquals("T1202N", CoverIndexRepository.normalizeSerial("T-1202N"))
        assertEquals("HDR0190", CoverIndexRepository.normalizeSerial("HDR-0190"))
        assertEquals("T1202N", CoverIndexRepository.normalizeSerial(" t-1202n "))
    }

    @Test
    fun rejectsInvalidSerials() {
        assertNull(CoverIndexRepository.normalizeSerial(null))
        assertNull(CoverIndexRepository.normalizeSerial(""))
        assertNull(CoverIndexRepository.normalizeSerial("   "))
        assertNull(CoverIndexRepository.normalizeSerial("ABC")) // less than 4 chars
    }

    @Test
    fun normalizesTitlesCorrectly() {
        assertEquals("sonic adventure 2", CoverIndexRepository.normalizeTitle("Sonic Adventure 2"))
        assertEquals("sonic adventure", CoverIndexRepository.normalizeTitle("Sonic Adventure™"))
        assertEquals("crazy taxi 2", CoverIndexRepository.normalizeTitle("Crazy Taxi® 2"))
        assertEquals("capcom vs snk", CoverIndexRepository.normalizeTitle("Capcom vs. SNK"))
        assertEquals("marvel vs capcom", CoverIndexRepository.normalizeTitle("Marvel vs. Capcom!"))
    }

    @Test
    fun extractsRomsetKeys() {
        assertEquals("18wheelr", CoverIndexRepository.romsetKey("18wheelr.zip"))
        assertEquals("18wheelr", CoverIndexRepository.romsetKey("18WHEELR.ZIP"))
        assertEquals("cvs2gd", CoverIndexRepository.romsetKey("arcade/cvs2gd.7z"))
        assertEquals("ikaruga", CoverIndexRepository.romsetKey("C:\\roms\\ikaruga.zip"))
        assertEquals("mslug6", CoverIndexRepository.romsetKey("mslug6"))
    }

    @Test
    fun rejectsInvalidRomsetNames() {
        assertNull(CoverIndexRepository.romsetKey(null))
        assertNull(CoverIndexRepository.romsetKey(""))
        assertNull(CoverIndexRepository.romsetKey("   "))
    }

    @Test
    fun repositoryBaseUrlPointsToEmuCoreHCoversMain() {
        assertEquals(
            "https://raw.githubusercontent.com/sashkinbro/EmuCoreH-Covers/main",
            CoverIndexRepository.BASE_URL
        )
    }

    @Test
    fun makeCoverUrlTransforms3DAndFlatStyles() {
        val base = CoverIndexRepository.BASE_URL
        
        // 3D Dark Dreamcast
        assertEquals(
            "$base/covers/3d/420.webp",
            CoverIndexRepository.makeCoverUrl("covers/420.jpg", style = com.sbro.emucoreh.data.AppPreferences.COVER_ART_STYLE_3D, isLight = false)
        )
        // 3D Light Dreamcast
        assertEquals(
            "$base/covers/3d/light/420.webp",
            CoverIndexRepository.makeCoverUrl("covers/420.jpg", style = com.sbro.emucoreh.data.AppPreferences.COVER_ART_STYLE_3D, isLight = true)
        )
        // Flat Dreamcast
        assertEquals(
            "$base/covers/420.jpg",
            CoverIndexRepository.makeCoverUrl("covers/420.jpg", style = com.sbro.emucoreh.data.AppPreferences.COVER_ART_STYLE_FLAT, isLight = false)
        )
        // 3D Dark Arcade
        assertEquals(
            "$base/arcade/3d/18wheelr.webp",
            CoverIndexRepository.makeCoverUrl("arcade/18wheelr.jpg", style = com.sbro.emucoreh.data.AppPreferences.COVER_ART_STYLE_3D, isLight = false)
        )
        // 3D Light Arcade
        assertEquals(
            "$base/arcade/3d/light/18wheelr.webp",
            CoverIndexRepository.makeCoverUrl("arcade/18wheelr.jpg", style = com.sbro.emucoreh.data.AppPreferences.COVER_ART_STYLE_3D, isLight = true)
        )
        // Flat Arcade
        assertEquals(
            "$base/arcade/18wheelr.jpg",
            CoverIndexRepository.makeCoverUrl("arcade/18wheelr.jpg", style = com.sbro.emucoreh.data.AppPreferences.COVER_ART_STYLE_FLAT, isLight = false)
        )
    }
}
