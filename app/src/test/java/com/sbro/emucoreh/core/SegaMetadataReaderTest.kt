package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class SegaMetadataReaderTest {
    @Test fun nonAsciiHeaderFallsBackToCleanFilenameAndKeepsProductCode() {
        val bytes = ByteArray(32768)
        "SEGA GENESIS".toByteArray().copyInto(bytes, 0x100)
        "ゴールデンアックス".toByteArray(charset("Shift_JIS")).copyInto(bytes, 0x120)
        "ゴールデンアックス".toByteArray(charset("Shift_JIS")).copyInto(bytes, 0x150)
        "GM 00004049-00".toByteArray().copyInto(bytes, 0x180)
        val metadata = SegaMetadataReader.parseHeader("Golden Axe (World).md", bytes)!!
        assertEquals("Golden Axe", metadata.title)
        assertEquals("GM 00004049-00", metadata.serial)
    }

    @Test fun genesisHeaderUsesOverseasTitleAndProductCodeWithoutFilenameGuessing() {
        val bytes = ByteArray(32768)
        "SEGA GENESIS".toByteArray().copyInto(bytes, 0x100)
        "DOMESTIC TITLE".toByteArray().copyInto(bytes, 0x120)
        "Sonic The Hedgehog".toByteArray().copyInto(bytes, 0x150)
        "GM 00001009-00".toByteArray().copyInto(bytes, 0x180)
        val result = SegaMetadataReader.parseHeader("game.bin", bytes)!!
        assertEquals("Sonic The Hedgehog", result.title)
        assertEquals("GM 00001009-00", result.serial)
        assertNull(SegaMetadataReader.parseHeader("track.bin", ByteArray(32768)))
    }
}
