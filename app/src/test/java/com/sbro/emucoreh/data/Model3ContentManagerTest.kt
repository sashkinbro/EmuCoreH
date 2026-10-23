package com.sbro.emucoreh.data

import org.junit.Assert.*
import org.junit.Test

class Model3ContentManagerTest {
    private fun game(name: String, platform: String? = null) = GameItem(
        title = name, path = "/qa/$name", fileName = name, fileSize = 1, lastModified = 0,
        platform = platform, serial = "test-serial"
    )

    @Test fun explicitModel3CoreAndKnownLegacySetsAreExcludedFromBothManagers() {
        assertTrue(game("renamed.zip", "MODEL_3").usesModel3Core())
        assertTrue(game("SRALLY2.ZIP").usesModel3Core())
        assertTrue(game("daytona2.zip").usesModel3Core())
        assertTrue(game("lostwsga.zip").usesModel3Core())
    }

    @Test fun flycastAndJgenesisKeepTheirExistingManagerCapabilities() {
        assertFalse(game("ikaruga.zip", "NAOMI").usesModel3Core())
        assertFalse(game("game.md", "GENESIS").usesModel3Core())
        assertFalse(game("srally2.chd", "DREAMCAST").usesModel3Core())
    }
}
