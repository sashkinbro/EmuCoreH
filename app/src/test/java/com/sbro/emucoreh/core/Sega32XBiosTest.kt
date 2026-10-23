package com.sbro.emucoreh.core

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class Sega32XBiosTest {
    @Test fun rolesHaveDistinctCanonicalNamesAndExpectedDumpSizes() {
        assertEquals(listOf(256, 2048, 1024), Sega32XBios.Part.entries.map { it.size })
        assertEquals(3, Sega32XBios.Part.entries.map { it.fileName }.toSet().size)
        assertEquals(listOf(0x5c12eae8L, 0xdd9c46b8L, 0xbfda1fe5L),
            Sega32XBios.Part.entries.map { it.crc32 })
    }

    @Test fun recognizesRoleNamesCaseInsensitively() {
        assertEquals(Sega32XBios.Part.M68K, Sega32XBios.partForFileName("32x_g_bios.bin"))
        assertEquals(Sega32XBios.Part.MASTER, Sega32XBios.partForFileName("32X_M_BIOS.BIN"))
        assertEquals(Sega32XBios.Part.SLAVE, Sega32XBios.partForFileName("32X_S_BIOS.bin"))
        assertNull(Sega32XBios.partForFileName("dc_boot.bin"))
        assertNull(Sega32XBios.partForFileName(null))
    }

    @Test fun sameSizeGarbageAndWrongRoleNeverCountAsInstalledFirmware() {
        for (part in Sega32XBios.Part.entries) {
            assertFalse(Sega32XBios.isValid(ByteArray(part.size), part))
            assertFalse(Sega32XBios.isValid(ByteArray(part.size) { 0xff.toByte() }, part))
            assertFalse(Sega32XBios.isValid(ByteArray(part.size - 1), part))
            assertFalse(Sega32XBios.isValid(ByteArray(part.size + 1), part))
        }
    }

    @Test fun oversizedDocumentReadStopsAfterTheSelectedRoleLimit() {
        for (part in Sega32XBios.Part.entries) {
            val input = ByteArrayInputStream(ByteArray(part.size + 10000))
            assertNull(Sega32XBios.readValidated(input, part))
            assertEquals(9999, input.available())
        }
    }
}
