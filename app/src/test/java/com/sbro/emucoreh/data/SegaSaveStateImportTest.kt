package com.sbro.emucoreh.data

import com.sbro.emucoreh.core.CoreSaveState
import com.sbro.emucoreh.core.EmulationPlatform
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream

class SegaSaveStateImportTest {
    private fun state(tag: Int) = CoreSaveState.wrap("JGLRSTA1".toByteArray() + byteArrayOf(tag.toByte(), 42), 1)
    private fun name(platform: EmulationPlatform) = "JG-${platform.name}-ABCD123456789012.01.rstate"

    @Test fun everySegaConsoleRoundTripsThroughDirectAndZipHeaders() {
        val platforms = listOf(EmulationPlatform.GENESIS, EmulationPlatform.SEGA_CD, EmulationPlatform.SEGA_32X,
            EmulationPlatform.SEGA_CD, EmulationPlatform.MASTER_SYSTEM, EmulationPlatform.GAME_GEAR, EmulationPlatform.SG_1000)
        platforms.forEachIndexed { tag, platform ->
            val bytes = state(tag)
            val header = readSaveStateImportHeader(bytes.inputStream())!!
            assertEquals(platform, header.platform)
            assertTrue(isSaveStateImportCompatible(header, name(platform), null))
            assertTrue(isSaveStateImportCompatible(header, name(platform), platform))
            assertFalse(isSaveStateImportCompatible(header, name(platform), EmulationPlatform.DREAMCAST))
            val archive = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("slots/${name(platform)}")); zip.write(bytes); zip.closeEntry()
            } }.toByteArray()
            ZipInputStream(archive.inputStream()).use { zip ->
                zip.nextEntry
                assertEquals(header, readSaveStateImportHeader(zip))
            }
        }
    }

    @Test fun rejectsUnknownCoreConsoleMalformedAndTruncatedHeaders() {
        assertNull(readSaveStateImportHeader(CoreSaveState.wrap(byteArrayOf(1), 1).inputStream()))
        assertNull(readSaveStateImportHeader(CoreSaveState.wrap("JGLRSTA1".toByteArray() + byteArrayOf(0, 1), 9).inputStream()))
        assertNull(readSaveStateImportHeader(state(7).inputStream()))
        for (length in 0..21) assertNull("length $length", readSaveStateImportHeader(state(0).copyOf(length).inputStream()))
    }

    @Test fun rejectsWrongConsoleAndMisleadingFileNamespaces() {
        val genesis = readSaveStateImportHeader(state(0).inputStream())!!
        assertFalse(isSaveStateImportCompatible(genesis, name(EmulationPlatform.GAME_GEAR), null))
        assertFalse(isSaveStateImportCompatible(genesis, name(EmulationPlatform.GENESIS), EmulationPlatform.GAME_GEAR))
        assertFalse(isSaveStateImportCompatible(genesis, "MK-51000.01.rstate", null))
        assertTrue(isSaveStateImportCompatible(genesis, "renamed.rstate", EmulationPlatform.GENESIS))
        val flycast = readSaveStateImportHeader(CoreSaveState.wrap(byteArrayOf(42), 0).inputStream())!!
        assertTrue(isSaveStateImportCompatible(flycast, "MK-51000.01.rstate", null))
        assertFalse(isSaveStateImportCompatible(flycast, name(EmulationPlatform.GENESIS), null))
        assertFalse(isSaveStateImportCompatible(flycast, "MK-51000.01.rstate", EmulationPlatform.GENESIS))
    }

    @Test fun preservesNamespacedHashesThatResembleLegacyProductCodes() {
        assertEquals("JG-GENESIS-ABCD123456789012", parseExternalSaveStateName(name(EmulationPlatform.GENESIS))!!.serial)
        assertEquals("SLUS-12345", parseExternalSaveStateName("SLUS_123.45.01.rstate")!!.serial)
    }

    @Test fun headerParserHandlesShortReads() {
        val source = object : ByteArrayInputStream(state(4)) {
            override fun read(buffer: ByteArray, off: Int, len: Int): Int = super.read(buffer, off, minOf(len, 1))
        }
        assertEquals(EmulationPlatform.MASTER_SYSTEM, readSaveStateImportHeader(source)!!.platform)
    }
}
