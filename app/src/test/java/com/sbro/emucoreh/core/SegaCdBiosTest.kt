package com.sbro.emucoreh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegaCdBiosTest {
    @Test fun recognizesCanonicalNamesAndKnownAliases() {
        assertEquals(SegaCdBios.Region.US, SegaCdBios.regionForFileName("bios_CD_U.bin"))
        assertEquals(SegaCdBios.Region.US, SegaCdBios.regionForFileName("BIOS_CD_US.BIN"))
        assertEquals(SegaCdBios.Region.US, SegaCdBios.regionForFileName("scd_us.bin"))
        assertEquals(SegaCdBios.Region.EU, SegaCdBios.regionForFileName("bios_cd_eu.bin"))
        assertEquals(SegaCdBios.Region.JP, SegaCdBios.regionForFileName("bios_CD_J.bin"))
        assertEquals(SegaCdBios.Region.JP, SegaCdBios.regionForFileName("bios_jp.bin"))
    }

    @Test fun ignoresUnrelatedNames() {
        assertNull(SegaCdBios.regionForFileName("dc_flash.bin"))
        assertNull(SegaCdBios.regionForFileName("32X_G_BIOS.bin"))
        assertNull(SegaCdBios.regionForFileName(null))
    }
}
