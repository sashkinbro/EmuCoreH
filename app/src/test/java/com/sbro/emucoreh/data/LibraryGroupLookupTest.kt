package com.sbro.emucoreh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryGroupLookupTest {
    private val disc1 = GameDisc("/Games/Disc1.chd", "Disc1.chd", 1, "SERIAL1", 100, 10)
    private val disc2 = GameDisc("/Games/Disc2.chd", "Disc2.chd", 2, "SERIAL2", 200, 20)
    private val grouped = GameItem("Game", "/Games/Game.m3u", "Game.m3u", 20, 30, discs = listOf(disc1, disc2))
    private val identity: (String) -> String = { it.lowercase() }

    @Test fun newestMemberHistoryResolvesToOneRepresentativeCard() {
        assertEquals(listOf(grouped), recentLibraryGroups(listOf(grouped), listOf(disc2.path.uppercase(), disc1.path), identity))
    }

    @Test fun playlistHistoryAndUnknownPathsDoNotDuplicateOrInventCards() {
        assertEquals(listOf(grouped), recentLibraryGroups(listOf(grouped), listOf("/missing", grouped.path, disc2.path), identity))
    }

    @Test fun cachedMembersKeepTheirOwnSizeAndSerialForTheNextScan() {
        val flat = flattenLibraryGroups(listOf(grouped)).associateBy { it.path }
        assertEquals(grouped, flat[grouped.path])
        assertEquals("SERIAL2", flat[disc2.path]?.serial)
        assertEquals(200L, flat[disc2.path]?.fileSize)
        assertEquals(emptyList<GameDisc>(), flat[disc2.path]?.discs)
    }

    @Test fun recentGroupsPreserveNewestOrderAcrossDifferentGames() {
        val other = GameItem("Other", "/Games/Other.chd", "Other.chd", 100, 5)
        assertEquals(listOf(other, grouped), recentLibraryGroups(listOf(grouped, other), listOf(other.path, disc2.path, disc1.path), identity))
    }
}
