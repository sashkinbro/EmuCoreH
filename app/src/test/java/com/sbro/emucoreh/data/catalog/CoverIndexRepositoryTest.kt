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
}
