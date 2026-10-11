package com.sbro.emucoreh.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscSwapFormatTest {
    @Test fun acceptsDiscImagesForAnAlreadyRunningDreamcast() {
        listOf("Disc 2.GDI", "Disc 2.chd", "Disc 2.cdi", "Disc 2.cue", "Disc 2.iso")
            .forEach { assertTrue(it, GameFormats.isDiscImageName(it)) }
    }

    @Test fun rejectsArcadeRomsPlaylistsAndRawTracksForDiscReplacement() {
        listOf("naomi.zip", "atomiswave.7z", "game.dat", "game.lst", "game.m3u", "track.bin", "")
            .forEach { assertFalse(it, GameFormats.isDiscImageName(it)) }
        assertTrue(GameFormats.isLaunchableName("naomi.zip"))
        assertTrue(GameFormats.isLaunchableName("game.m3u"))
    }
}
