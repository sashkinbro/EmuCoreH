package com.sbro.emucoreh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SegaCdBiosTest {
    private fun firmware(region: Char) = ByteArray(SegaCdBios.SIZE).apply {
        "SEGA MEGA DRIVE ".toByteArray().copyInto(this, 0x100)
        "CD2 BOOT ROM".toByteArray().copyInto(this, 0x120)
        this[0x1f0] = region.code.toByte()
    }

    @Test fun detectsRegionalFirmwareFromHeaderRegardlessOfName() {
        assertEquals(SegaCdBios.Region.US, SegaCdBios.detectRegion(firmware('U')))
        assertEquals(SegaCdBios.Region.EU, SegaCdBios.detectRegion(firmware('E')))
        assertEquals(SegaCdBios.Region.JP, SegaCdBios.detectRegion(firmware('J')))
    }

    @Test fun rejectsFlashRomCartridgeAndAmbiguousRegions() {
        assertNull(SegaCdBios.detectRegion(ByteArray(SegaCdBios.SIZE)))
        assertNull(SegaCdBios.detectRegion(firmware('U').apply { fill(0, 0x120, 0x150) }))
        assertNull(SegaCdBios.detectRegion(firmware('U').apply { this[0x1f1] = 'E'.code.toByte() }))
        assertNull(SegaCdBios.detectRegion(firmware('U').copyOf(SegaCdBios.SIZE - 1)))
    }

    @Test fun normalizesWordSwappedDumpsBeforeInstallation() {
        val original = firmware('E')
        val swapped = original.copyOf().apply {
            for (i in indices step 2) { val first = this[i]; this[i] = this[i + 1]; this[i + 1] = first }
        }
        assertArrayEquals(original, SegaCdBios.normalizeDetected(swapped))
        assertNull(SegaCdBios.normalizeDetected(ByteArray(SegaCdBios.SIZE)))
    }

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
