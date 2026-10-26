package com.sbro.emucoreh.data

import org.junit.Assert.*
import org.junit.Test

class PlayerGameIdentityTest {
    @org.junit.Test fun saturnAndDreamcastGameTotalsRemainIndependent() {
        org.junit.Assert.assertNotEquals(playerGameKey("MK-12345", "/game.chd", "Saturn"),
            playerGameKey("MK-12345", "/game.chd", "Dreamcast"))
    }
    @Test fun oldDreamcastAndArcadeKeysRemainUnchanged() {
        assertEquals("MK-51035", playerGameKey("mk-51035", "/dc/game.chd", null))
        assertEquals("MK-51035", playerGameKey("mk-51035", "/dc/game.chd", "DREAMCAST"))
        assertEquals("SONIC", playerGameKey("sonic", "/arcade/sonic.zip", null))
        assertEquals(playerGameKey(null, "/dc/game.chd", null), playerGameKey(null, "/dc/game.chd", "DREAMCAST"))
    }
    @Test fun identicalTitlesAndSerialsDoNotMergeAcrossSystems() {
        assertNotEquals(playerGameKey("SONIC", "same", "GENESIS"), playerGameKey("SONIC", "same", "MASTER_SYSTEM"))
        assertNotEquals(playerGameKey("SONIC", "same", null), playerGameKey("SONIC", "same", "GENESIS"))
        assertNotEquals(playerGameKey(null, "same", "GENESIS"), playerGameKey(null, "same", "GAME_GEAR"))
        assertEquals(playerGameKey("ABC", "path", "SEGA_CD"), playerGameKey("ABC", "path", "Sega CD"))
    }
    @Test fun absentPlatformInLegacySerializedRecordStaysNull() {
        assertNull(playerGamePlatform(emptyMap()))
        assertNull(playerGamePlatform(mapOf("p" to "")))
        assertEquals("GENESIS", playerGamePlatform(mapOf("p" to "GENESIS")))
    }
}
