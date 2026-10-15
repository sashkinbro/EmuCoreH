package com.sbro.emucoreh.data.boxart

import org.junit.Assert.*
import org.junit.Test

class DiscScrapeAccessTest {
    @Test fun `first CHD scrape uses the folder grant rather than an unreadable raw Android path`() {
        val attempted = mutableListOf<String>()
        val result = DiscScrapeAccess.read("content://games/soul.chd",
            prepareSaf = { "/__emucoreh_saf__/mount/soul.chd" },
            resolveLocal = { "/storage/ABCD/games/soul.chd" },
            scrape = { path -> attempted += path; if (path.startsWith("/__emucoreh_saf__/")) "disc PNG" else null })
        assertEquals("disc PNG", result)
        assertEquals(listOf("/__emucoreh_saf__/mount/soul.chd"), attempted)
    }

    @Test fun `failed SAF driver read can fall back to a readable local image`() {
        val attempted = mutableListOf<String>()
        val result = DiscScrapeAccess.read("content://games/soul.chd",
            prepareSaf = { "/__emucoreh_saf__/mount/soul.chd" },
            resolveLocal = { "/storage/ABCD/games/soul.chd" },
            scrape = { path -> attempted += path; if (path.startsWith("/storage/")) "disc PNG" else null })
        assertEquals("disc PNG", result)
        assertEquals(listOf("/__emucoreh_saf__/mount/soul.chd", "/storage/ABCD/games/soul.chd"), attempted)
    }

    @Test fun `local disc scrape needs neither a document provider nor a network source`() {
        assertEquals("disc PNG", DiscScrapeAccess.read("/games/soul.gdi",
            prepareSaf = { error("Local discs must not query SAF") },
            resolveLocal = { error("Local discs already have their path") },
            scrape = { if (it == "/games/soul.gdi") "disc PNG" else null }))
    }
}
