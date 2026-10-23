package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class SegaContentIdentityTest {
    @Test fun discsOfTheSameGameShareBatteryRam() {
        assertEquals(SegaContentIdentity.batteryKey("/roms/Game (Disc 1).cue"),
            SegaContentIdentity.batteryKey("/roms/Game (Disc 2).chd"))
    }
    @Test fun identicalNamesInDifferentFoldersCannotOverwriteEachOther() {
        assertNotEquals(SegaContentIdentity.batteryKey("/roms/US/Game.md"),
            SegaContentIdentity.batteryKey("/roms/JP/Game.md"))
    }
    @Test fun usesOriginalSafIdentityAndProducesSafeFileNames() {
        val key = SegaContentIdentity.batteryKey("content://provider/roms%2FGame.md", "Game.md")
        assertTrue(key.matches(Regex("[a-z0-9_-]+")))
        assertEquals(key, SegaContentIdentity.batteryKey("content://provider/roms%2FGame.md", "Game.md"))
    }
}
