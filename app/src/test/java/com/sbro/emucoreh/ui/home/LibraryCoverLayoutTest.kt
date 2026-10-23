package com.sbro.emucoreh.ui.home

import org.junit.Assert.*
import org.junit.Test

class LibraryCoverLayoutTest {
    @Test fun legacyCasesKeepTheirConfiguredAspect() {
        for (platform in listOf(null, "DREAMCAST", "NAOMI", "ATOMISWAVE")) {
            assertEquals(1.2f, libraryCoverAspectRatio(platform, 1.2f), 0f)
        }
    }
    @Test fun segaCasesReservePortraitSpaceInsteadOfSquareExportCanvas() {
        for (platform in listOf("GENESIS", "SEGA_CD", "SEGA_32X", "MASTER_SYSTEM", "GAME_GEAR", "SG_1000")) {
            val aspect = libraryCoverAspectRatio(platform, 1f)
            assertTrue(aspect in 0.7f..0.8f)
        }
    }
}
