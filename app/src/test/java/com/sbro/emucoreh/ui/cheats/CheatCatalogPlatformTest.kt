package com.sbro.emucoreh.ui.cheats

import com.sbro.emucoreh.data.RemoteCheatPack
import org.junit.Assert.*
import org.junit.Test

class CheatCatalogPlatformTest {
    private fun pack(id: String, title: String, platform: String) = RemoteCheatPack(
        id = id, title = title, serials = emptyList(), crc = "", authors = listOf("Test"),
        description = "", downloadUrl = "https://example.com/code", sourceUrl = "https://example.com/source",
        sourceName = "Test", license = "CC0", blockCount = 1, platform = platform
    )

    @Test fun segaMatchesOnlyTheSamePlatformAndCompleteRevision() {
        val us = pack("us", "Test Game (USA)", "GENESIS")
        val eu = pack("eu", "Test Game (Europe)", "GENESIS")
        val dc = pack("dc", "Test Game (USA)", "FLYCAST")
        val result = selectRemoteCheatPacks(indexRemoteCheatCatalog(listOf(us, eu, dc)),
            serial = "JG-GENESIS-1234", crc = null, gameTitle = "Test Game (USA)", platform = "GENESIS")
        assertEquals(listOf(us), result.matchingPacks)
        assertEquals(listOf(eu), result.otherVersionPacks)
    }

    @Test fun missingRevisionCannotAutomaticallyInstallSegaCodes() {
        val us = pack("us", "Test Game (USA)", "GENESIS")
        val result = selectRemoteCheatPacks(indexRemoteCheatCatalog(listOf(us)),
            serial = "JG-GENESIS-1234", crc = null, gameTitle = "Test Game", platform = "GENESIS")
        assertTrue(result.matchingPacks.isEmpty())
        assertEquals(listOf(us), result.otherVersionPacks)
    }
}
