package com.sbro.emucoreh.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiDiscCacheCodecTest {
    @Test fun segaPlatformSurvivesCacheRoundTripAndLegacyCacheDefaultsToNull() {
        val game = GameItem("Game", "/game.md", "game.md", 1, 1, platform = "GENESIS")
        assertEquals(game, game.toLibraryCacheJson().readCachedGameItem())
        assertEquals(null, JSONObject("""{"path":"/game.chd","file_name":"game.chd"}""").readCachedGameItem()!!.platform)
    }

    @Test
    fun groupedPlaylistRoundTripsEveryPhysicalDiscField() {
        val game = GameItem(
            title = "Adventure", path = "content://provider/playlist", fileName = "Adventure.m3u",
            fileSize = 100, lastModified = 500, coverArtPath = "/cover.png", serial = "T-1000", discSize = 600,
            discs = listOf(
                GameDisc("content://provider/disc2", "Disc 2.chd", 1, "T-2000", 200, 400, 250),
                GameDisc("content://provider/disc1", "Disc 1.gdi", 2, null, 300, 700, null)
            )
        )

        assertEquals(game, game.toLibraryCacheJson().readCachedGameItem())
    }

    @Test
    fun cachedSingleDiscWithoutMembersIsStillReadable() {
        val json = JSONObject("""{"title":"Single","path":"/games/Single.chd","file_name":"Single.chd","file_size":100,"last_modified":10}""")

        val game = json.readCachedGameItem()!!

        assertTrue(game.discs.isEmpty())
        assertEquals("/games/Single.chd", game.launchDiscs().single().path)
    }

    @Test
    fun invalidCachedMemberCannotLeaveAnIncompleteGroup() {
        val json = JSONObject("""{"title":"Adventure","path":"/games/Adventure.m3u","file_name":"Adventure.m3u","discs":[{"path":"/games/1.chd","file_name":"1.chd","number":1},{"path":"","file_name":"2.chd","number":2}]}""")

        assertTrue(json.readCachedGameItem()!!.discs.isEmpty())
    }
}
