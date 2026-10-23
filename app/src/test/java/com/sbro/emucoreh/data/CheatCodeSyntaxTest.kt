package com.sbro.emucoreh.data

import org.junit.Assert.*
import org.junit.Test

class CheatCodeSyntaxTest {
    @Test fun deviceCodeSpellingIsPreserved() {
        val codes = listOf("97JT-EVSG", "FF0000:00E0", "00C00004", "00C0-0004", "00A-17B-C49")
        assertEquals(codes, CheatCodeSyntax.splitLibretroCodes(codes.joinToString("+")))
    }
    @Test fun twoSmsCodesAreNotMistakenForFlycastAddressAndPayload() {
        assertEquals(listOf("00C00004", "00C00105"),
            CheatCodeSyntax.splitLibretroCodes("00C00004+00C00105"))
    }
    @Test fun legacyFlycastPairsRemainPaired() {
        assertEquals(listOf("0C123456 00000009", "0C654321 00000001"),
            CheatCodeSyntax.splitLibretroCodes("0C123456+00000009+0C654321+00000001"))
        assertFalse(CheatCodeSyntax.isSupportedLine("not a code"))
    }
}
