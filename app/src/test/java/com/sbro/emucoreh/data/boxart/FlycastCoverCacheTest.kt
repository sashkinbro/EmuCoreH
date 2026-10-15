package com.sbro.emucoreh.data.boxart

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class FlycastCoverCacheTest {
    private fun withCache(test: (File, FlycastCoverCache) -> Unit) {
        val root = Files.createTempDirectory("flycast-cover-cache").toFile()
        try {
            test(root, FlycastCoverCache(root) { it.isFile && it.length() > 0 })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `first offline launch displays disc artwork in both 3D themes`() = withCache { root, cache ->
        val label = File(root, "T1401N.png").apply { writeText("disc artwork") }
        assertEquals(label, cache.find(listOf("T1401N"), style = 2, isLight = false))
        assertEquals(label, cache.find(listOf("T1401N"), style = 2, isLight = true))
    }

    @Test fun `disc artwork remains offline even after downloading a themed cover`() = withCache { root, cache ->
        val label = cache.offlineFile("T1401N").apply { writeText("disc artwork") }
        File(root, "T1401N.dark.url").writeText("https://repo/covers/3d/7767.webp")
        File(root, "T1401N.url").writeText("https://repo/covers/3d/7767.webp")
        assertFalse(cache.isOnline(label))
        assertEquals(label, cache.find(listOf("T1401N"), style = 2, isLight = true))
    }

    @Test fun `requested online style replaces disc fallback without leaking the previous style`() = withCache { root, cache ->
        val label = cache.offlineFile("T1401N").apply { writeText("disc artwork") }
        val flat = cache.onlineFile("T1401N", "jpg").apply { writeText("flat artwork") }
        assertEquals(label, cache.find(listOf("T1401N"), style = 2, isLight = false))
        assertEquals(flat, cache.find(listOf("T1401N"), style = 1))
        val dark = cache.onlineFile("T1401N", "webp", false).apply { writeText("dark 3D artwork") }
        assertEquals(dark, cache.find(listOf("T1401N"), style = 2, isLight = false))
        assertEquals(label, cache.find(listOf("T1401N"), style = 2, isLight = true))
        assertTrue(label.isFile)
    }

    @Test fun `title matching online artwork wins over a serial disc fallback`() = withCache { root, cache ->
        cache.offlineFile("T1401N").writeText("disc artwork")
        val online = cache.onlineFile("soulcalibur", "webp", true).apply { writeText("light 3D artwork") }
        assertEquals(online, cache.find(listOf("T1401N", "soulcalibur"), style = 2, isLight = true))
    }

    @Test fun `flat online artwork remains usable after downloading 3D for the same game`() = withCache { root, cache ->
        val flat = cache.onlineFile("soul", "jpg").apply { writeText("flat artwork") }
        cache.onlineFile("soul", "webp", true).writeText("light 3D artwork")
        File(root, "soul.url").writeText("https://repo/covers/3d/light/7767.webp")
        assertEquals(flat, cache.find(listOf("soul"), style = 1))
    }

    @Test fun `cover art disabled does not expose the disc fallback`() = withCache { _, cache ->
        cache.offlineFile("T1401N").writeText("disc artwork")
        assertNull(cache.find(listOf("T1401N"), style = 0))
    }

    @Test fun `failed extraction cannot replace a usable offline cover with an empty file`() = withCache { _, cache ->
        val label = cache.offlineFile("soul").apply { writeText("valid disc artwork") }
        cache.storeOffline("soul") { it.writeText("") }
        assertEquals("valid disc artwork", label.readText())
        assertEquals(label, cache.find(listOf("soul"), style = 2))
    }

    @Test fun `successful extraction is published offline without any online source marker`() = withCache { root, cache ->
        val stored = cache.storeOffline("soul") { it.writeText("disc artwork") }
        assertNotNull(stored)
        assertEquals(stored, cache.find(listOf("soul"), style = 2, isLight = true))
        assertFalse(cache.isOnline(stored!!))
        assertFalse(root.listFiles().orEmpty().any { it.extension == "url" || it.extension == "tmp" })
    }

    @Test fun `online PNG uses a different path from the displayed disc label`() = withCache { root, cache ->
        val label = File(root, "MK-51037.png").apply { writeText("disc label") }
        val online = cache.onlineFile("MK-51037", "png").apply { writeText("front cover") }
        File(root, "MK-51037.url").writeText("https://covers/front.png")
        assertNotEquals(label.absolutePath, online.absolutePath)
        assertEquals("disc label", label.readText())
        assertEquals(online, cache.find(listOf("MK-51037")))
        assertTrue(cache.isOnline(online))
    }

    @Test fun `title online artwork wins over serial offline label`() = withCache { root, cache ->
        File(root, "MK-51037.png").writeText("disc label")
        val titleCover = File(root, "daytona.jpg").apply { writeText("online cover") }
        File(root, "daytona.url").writeText("https://covers/front.jpg")
        assertEquals(titleCover, cache.find(listOf("MK-51037", "daytona")))
    }

    @Test fun `invalid source marker does not finalize the disc label`() = withCache { root, cache ->
        val label = File(root, "MK-51037.png").apply { writeText("disc label") }
        File(root, "MK-51037.url").writeText("")
        assertFalse(cache.isOnline(label))
    }

    @Test fun `old catalog miss does not block newly mapped artwork`() = withCache { root, cache ->
        File(root, "MK-51037.miss").writeText("not-found")
        File(root, "MK-51037.miss-v3").writeText("not-found")
        assertFalse(cache.missFile("MK-51037").exists())
    }

    @Test fun `raw disc code is tried before a normalized library serial`() {
        assertEquals(listOf("MK-5103750", "MK-51037"),
            FlycastBoxartSource.uniqueIdCandidates("MK-5103750", "MK-51037"))
        assertEquals(listOf("HDR-0053"), FlycastBoxartSource.uniqueIdCandidates("HDR-0053", "HDR-0053"))
        assertTrue(FlycastBoxartSource.uniqueIdCandidates("T0000M", null).isEmpty())
    }

    @Test fun `new label ignores stale online provenance after image eviction`() = withCache { root, cache ->
        File(root, "MK-51037.url").writeText("https://covers/evicted.png")
        val label = cache.offlineFile("MK-51037").apply { writeText("new disc label") }
        assertFalse(cache.isOnline(label))
    }

    @Test fun `online filename stays final when optional source URL write failed`() = withCache { root, cache ->
        val online = File(root, "MK-51037.online.png").apply { writeText("front cover") }
        assertTrue(cache.isOnline(online))
    }

    @Test fun `arcade lookup uses the first title without region suffixes like Flycast`() {
        assertEquals("Virtua Tennis", FlycastBoxartSource.searchName("Virtua Tennis / Power Smash (Japan)", arcade = true))
        assertEquals("Soul Calibur", FlycastBoxartSource.searchName("Soul Calibur (Japan) [Rev A]", arcade = true))
        assertEquals("My Dreamcast Game (Special)", FlycastBoxartSource.searchName("My Dreamcast Game (Special)", arcade = false))
    }
}
