package com.sbro.emucoreh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheatKeyMatcherTest {

    @Test
    fun matchesCompactAndDashedSerial() {
        assertTrue(CheatKeyMatcher.matches("MK51035", "MK-51035"))
        assertTrue(CheatKeyMatcher.matches("MK-51035", "MK51035"))
    }

    @Test
    fun matchesSerialAgainstSerialCrcFile() {
        assertTrue(CheatKeyMatcher.matches("MK-51035", "MK-51035_D8B2E8A0"))
        assertTrue(CheatKeyMatcher.matches("T-9706D", "T-9706D_D8B2E8A0"))
        assertTrue(CheatKeyMatcher.matches("MK-51035_D8B2E8A0", "MK-51035"))
    }

    @Test
    fun matchesCrcOnlyKeyAgainstSerialCrcFile() {
        assertTrue(CheatKeyMatcher.matches("D8B2E8A0", "MK-51035_D8B2E8A0"))
    }

    @Test
    fun rejectsConflictingCrc() {
        assertFalse(CheatKeyMatcher.matches("MK-51035_DEADBEEF", "MK-51035_D8B2E8A0"))
    }

    @Test
    fun matchesSerialWithRevisionDigitsDropped() {
        assertTrue(CheatKeyMatcher.matches("MK-5103750", "MK-51037"))
    }

    @Test
    fun matchesHdrStyleSerialWithUnderscore() {
        assertTrue(CheatKeyMatcher.matches("HDR-0053", "HDR_0053"))
    }

    @Test
    fun matchesTitlesThatOnlyDifferBySeparators() {
        assertTrue(CheatKeyMatcher.matches("Crazy Taxi 2", "Crazy_Taxi_2"))
        assertTrue(CheatKeyMatcher.matches("18 Wheeler", "18_Wheeler"))
    }

    @Test
    fun rejectsUnrelatedKeys() {
        assertFalse(CheatKeyMatcher.matches("Crazy Taxi 2", "Sonic Adventure 2"))
        assertFalse(CheatKeyMatcher.matches("MK-51035", "T-9706D"))
    }

    @Test
    fun decomposedSignatureIgnoresSeparatorsAndCase() {
        assertTrue(
            CheatKeyMatcher.matches("mk51035", "MK-51035")
        )
        assertTrue(
            CheatKeyMatcher.matches("t_9706d", "T-9706D")
        )
    }
}
