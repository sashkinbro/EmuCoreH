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

    @Test fun `old poisoned miss marker does not block the fixed lookup`() = withCache { root, cache ->
        File(root, "MK-51037.miss").writeText("not-found")
        assertFalse(cache.missFile("MK-51037").exists())
    }

    @Test fun `raw disc code is tried before a normalized library serial`() {
        assertEquals(listOf("MK-5103750", "MK-51037"),
            FlycastBoxartSource.uniqueIdCandidates("MK-5103750", "MK-51037"))
        assertEquals(listOf("HDR-0053"), FlycastBoxartSource.uniqueIdCandidates("HDR-0053", "HDR-0053"))
        assertTrue(FlycastBoxartSource.uniqueIdCandidates("T0000M", null).isEmpty())
    }

    @Test fun `new label clears stale online provenance after image eviction`() = withCache { root, cache ->
        File(root, "MK-51037.url").writeText("https://covers/evicted.png")
        cache.clearOnlineSource("MK-51037")
        val label = File(root, "MK-51037.png").apply { writeText("new disc label") }
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
