package com.sbro.emucoreh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscSerialTest {

    @Test
    fun keepsProductNumbersUnchanged() {
        assertEquals("MK-51035", DiscSerial.normalize("MK-51035"))
        assertEquals("T-9701N", DiscSerial.normalize("T-9701N"))
        assertEquals("HDR-0053", DiscSerial.normalize("hdr-0053"))
    }

    @Test
    fun addsTheSeparatorToCompactBootstrapCodes() {
        assertEquals("T-3601N", DiscSerial.normalize("T3601N"))
        assertEquals("HDR-0053", DiscSerial.normalize("HDR0053"))
    }

    @Test
    fun dropsDiscRevisionSuffix() {
        assertEquals("MK-51037", DiscSerial.normalize("MK-5103750"))
        assertEquals("MK-51059", DiscSerial.normalize("MK-5105950"))
        assertEquals("MK-51010", DiscSerial.normalize("MK-5101050"))
    }

    @Test
    fun rejectsUnusableValues() {
        assertNull(DiscSerial.normalize(""))
        assertNull(DiscSerial.normalize("DREAMCAST"))
        assertNull(DiscSerial.normalize("1-2345"))
    }
}
