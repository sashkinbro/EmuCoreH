package com.sbro.emucoreh.data

import com.sbro.emucoreh.core.CoreSaveState
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.core.SaturnSaveFiles
import org.junit.Assert.*
import org.junit.Test

class SaturnSaveStateImportTest {
    private fun payload(version: Int = SaturnSaveFiles.STATE_VERSION, size: Int = 64): ByteArray {
        val bytes = ByteArray(size)
        "MDFNSVST".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            putInt(16, version)
            putInt(20, size)
        }
        return bytes
    }

    @Test fun importsBeetleSaturnEnvelopeUnderItsOwnNamespaceAndRejectsOtherCores() {
        val bytes = CoreSaveState.wrap(payload(), 3)
        val header = readSaveStateImportHeader(bytes.inputStream())!!
        val name = "SS-ABCD123456789012.01.rstate"
        assertEquals(EmulationPlatform.SATURN, header.platform)
        assertTrue(isSaveStateImportCompatible(header, name, EmulationPlatform.SATURN))
        assertFalse(isSaveStateImportCompatible(header, name, EmulationPlatform.DREAMCAST))
        assertFalse(isSaveStateImportCompatible(header, "MK-51035.01.rstate", null))
        val dreamcast = SaveStateImportHeader(1, 0)
        assertFalse(isSaveStateImportCompatible(dreamcast, name, null))
        assertEquals("SS-ABCD123456789012", parseExternalSaveStateName(name)!!.serial)
    }

    @Test fun rejectsEmptyTruncatedJunkWrongEndianAndMismatchedDeclaredSizes() {
        val valid = CoreSaveState.wrap(payload(), 3)
        for (length in 0 until valid.size) assertNull("Truncated at $length", readSaveStateImportHeader(valid.copyOf(length).inputStream()))
        val mutations = listOf(
            ByteArray(100),
            payload().apply { this[0] = 0 },
            payload().apply { this[16] = 3 },
            payload().apply { this[20] = 0 },
            payload().apply { this[20] = 17 },
            payload() + byteArrayOf(0)
        )
        for (bytes in mutations) assertNull(readSaveStateImportHeader(CoreSaveState.wrap(bytes, 3).inputStream()))
    }

    @Test fun rejectsStatesFromAnotherCoreVersion() {
        val old = CoreSaveState.wrap(payload(version = 0x00102201), 3)
        assertNull(readSaveStateImportHeader(old.inputStream()))
        val current = CoreSaveState.wrap(payload(version = SaturnSaveFiles.STATE_VERSION), 3)
        assertNotNull(readSaveStateImportHeader(current.inputStream()))
    }

    @Test fun supportsShortReadProviders() {
        val source = object : java.io.ByteArrayInputStream(CoreSaveState.wrap(payload(), 3)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(1, length))
        }
        assertEquals(EmulationPlatform.SATURN, readSaveStateImportHeader(source)?.platform)
    }
}
