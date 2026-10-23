package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class SegaOptionNamespaceTest {
    @Test fun commonOptionsAreIndependentAcrossSelectedSystems() {
        assertNotEquals(segaOptionStorageKey(EmulationPlatform.GENESIS, "jgenesis_region"),
            segaOptionStorageKey(EmulationPlatform.GAME_GEAR, "jgenesis_region"))
        val saved = mapOf(segaOptionStorageKey(EmulationPlatform.GENESIS, "jgenesis_region") to "japan")
        assertNull(saved[segaOptionStorageKey(EmulationPlatform.SEGA_CD, "jgenesis_region")])
    }
}
