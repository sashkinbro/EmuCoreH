package com.sbro.emucoreh.data

import com.sbro.emucoreh.core.CoreSaveState
import com.sbro.emucoreh.core.EmulationPlatform
import org.junit.Assert.*
import org.junit.Test

class SaturnSaveStateImportTest {
    private fun payload(version: Int = 2): ByteArray = java.nio.ByteBuffer.allocate(if (version == 2) 36 else 28)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("YSS".toByteArray()); put(1.toByte()); putInt(version); putInt(16)
            if (version == 2) { putInt(0); putInt(0) }
            put(ByteArray(16))
        }.array()

    @Test fun importsSaturnEnvelopeUnderItsOwnNamespaceAndRejectsOtherCores() {
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
            payload().apply { this[3] = 0 },
            payload().apply { this[4] = 3 },
            payload().apply { this[8] = 0 },
            payload().apply { this[8] = 17 },
            payload() + byteArrayOf(0)
        )
        for (bytes in mutations) assertNull(readSaveStateImportHeader(CoreSaveState.wrap(bytes, 3).inputStream()))
        assertNotNull(readSaveStateImportHeader(CoreSaveState.wrap(payload(1), 3).inputStream()))
    }

    @Test fun supportsShortReadProviders() {
        val source = object : java.io.ByteArrayInputStream(CoreSaveState.wrap(payload(), 3)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(1, length))
        }
        assertEquals(EmulationPlatform.SATURN, readSaveStateImportHeader(source)?.platform)
    }
}
