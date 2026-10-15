package com.sbro.emucoreh.data.boxart

import com.sbro.emucoreh.data.GameItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class InitialCoverSyncTest {
    private val games = (1..8).map { GameItem("Game $it", "/$it.chd", "$it.chd", 1, 0) }

    @Test fun `all repository covers are published before a slow disc can block the library`() = runBlocking {
        val discStarted = CompletableDeferred<Unit>()
        val releaseDisc = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        val sync = async {
            syncInitialCovers(games, true, { false }, fetch = { game, mode ->
                if (mode == CoverFetchMode.REPOSITORY_ONLY) {
                    if (game == games.first()) null else "repo:${game.path}"
                }
                else { discStarted.complete(Unit); releaseDisc.await(); null }
            }, publish = { _, path -> published += path })
        }
        try {
            withTimeout(5000) { discStarted.await() }
            assertEquals(games.drop(1).map { "repo:${it.path}" }.toSet(), published.toSet())
        } finally {
            releaseDisc.complete(Unit)
            sync.await()
        }
    }

    @Test fun `offline startup only reads local artwork`() = runBlocking {
        val published = mutableListOf<String>()
        assertFalse(syncInitialCovers(games, false, { false }, fetch = { game, mode ->
            assertEquals(CoverFetchMode.OFFLINE_ONLY, mode)
            "disc:${game.path}"
        }, publish = { _, path -> published += path }).deferred)
        assertEquals(games.map { "disc:${it.path}" }.toSet(), published.toSet())
    }

    @Test fun `starting a game defers cover work without accessing network or discs`() = runBlocking {
        assertTrue(syncInitialCovers(games, true, { true },
            fetch = { _, _ -> error("The emulator is active") }, publish = { _, _ -> error("No result") }).deferred)
    }

    @Test fun `a library of repository hits never schedules offline extraction`() = runBlocking {
        val published = mutableListOf<String>()
        assertFalse(syncInitialCovers(games, true, { false }, fetch = { game, mode ->
            assertEquals(CoverFetchMode.REPOSITORY_ONLY, mode)
            "repo:${game.path}"
        }, publish = { _, path -> published += path }).deferred)
        assertEquals(8, published.size)
    }

    @Test fun `a broken connection stops the remaining fast requests and allows all local covers`() = runBlocking {
        var connectionReady = true
        var requests = 0
        val published = mutableListOf<String>()
        val result = syncInitialCovers(games, true, { false }, fetch = { game, mode ->
            if (mode == CoverFetchMode.REPOSITORY_ONLY) {
                requests++
                connectionReady = false
                null
            } else "disc:${game.path}"
        }, publish = { _, path -> published += path }, allowRepository = { connectionReady })
        assertEquals(1, requests)
        assertEquals(8, published.size)
        assertFalse("The upgrade pass must remain postponed after the fast phase's outage", result.canUpgradeOnline)
    }

    @Test fun `cached labels returned by a failed fast request remain eligible for disc fallback`() = runBlocking {
        val modes = mutableListOf<CoverFetchMode>()
        syncInitialCovers(games.take(1), true, { false }, fetch = { _, mode ->
            modes += mode
            "disc:cached"
        }, publish = { _, _ -> }, isFinalCover = { !it.startsWith("disc:") })
        assertEquals(listOf(CoverFetchMode.REPOSITORY_ONLY, CoverFetchMode.OFFLINE_ONLY), modes)
    }
}
