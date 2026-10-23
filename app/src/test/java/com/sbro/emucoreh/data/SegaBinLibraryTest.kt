package com.sbro.emucoreh.data

import com.sbro.emucoreh.core.EmulationPlatform
import org.junit.Assert.*
import org.junit.Test

class SegaBinLibraryTest {
    @Test fun rawTracksAndUnknownBinsAreHiddenButCartridgesRemainVisible() {
        assertFalse(GameRepository.isStandaloneSegaBin("track.bin", EmulationPlatform.SEGA_CD, false))
        assertFalse(GameRepository.isStandaloneSegaBin("track.bin", EmulationPlatform.DREAMCAST, false))
        assertFalse(GameRepository.isStandaloneSegaBin("track.bin", EmulationPlatform.GENESIS, true))
        assertTrue(GameRepository.isStandaloneSegaBin("game.BIN", EmulationPlatform.GENESIS, false))
    }
}
