package com.sbro.emucoreh.data.boxart

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class FlycastImageDownloaderTest {
    private fun withImage(status: Int, body: String, test: (FlycastImageDownloader, String, File) -> Unit) {
        val root = Files.createTempDirectory("flycast-cover-download").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/cover.png") { exchange ->
            exchange.responseHeaders.add("Content-Type", "image/png")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            // The image decoder boundary is injected; HTTP, temporary files,
            // validation/commit order and provenance are real production code.
            val downloader = FlycastImageDownloader(root) { it.isFile && it.readText() == "valid cover" }
            test(downloader, "http://127.0.0.1:${server.address.port}/cover.png", root)
        } finally {
            server.stop(0)
            root.deleteRecursively()
        }
    }

    @Test fun `arcade CDN outage remains retryable and does not cache a miss`() = withImage(503, "outage") { downloader, url, root ->
        try {
            downloader.download(url, "tekken")
            fail("Temporary image outage must not be treated as a missing cover")
        } catch (failure: BoxartRetryException) {
            assertTrue(failure.retryAtMillis > System.currentTimeMillis())
        }
        assertFalse(FlycastCoverCache(root) { it.isFile }.missFile("tekken").exists())
        assertFalse(File(root, "tekken.url").exists())
    }

    @Test fun `image 404 permits another artwork source`() = withImage(404, "missing") { downloader, url, root ->
        assertNull(downloader.download(url, "tekken"))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun `online PNG replaces label with a distinct observable path`() = withImage(200, "valid cover") { downloader, url, root ->
        val label = File(root, "daytona.png").apply { writeText("disc label") }
        val downloaded = downloader.download(url, "daytona")
        assertEquals(File(root, "daytona.online.png").absolutePath, downloaded)
        assertEquals("valid cover", File(downloaded!!).readText())
        assertFalse(label.exists())
        assertEquals(url, File(root, "daytona.url").readText())
        assertFalse(root.listFiles().orEmpty().any { it.extension == "tmp" })
    }

    @Test fun `invalid download retains the usable disc label for a retry`() = withImage(200, "broken image") { downloader, url, root ->
        val label = File(root, "daytona.png").apply { writeText("disc label") }
        try {
            downloader.download(url, "daytona")
            fail("An invalid download is not a definitive missing cover")
        } catch (_: BoxartRetryException) {
            assertEquals("disc label", label.readText())
        }
        assertFalse(File(root, "daytona.url").exists())
        assertFalse(root.listFiles().orEmpty().any { it.extension == "tmp" })
    }

    @Test fun `offline disc survives an outage and upgrades to the repository cover when retry succeeds`() {
        val root = Files.createTempDirectory("flycast-offline-upgrade").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val status = AtomicInteger(503)
        val requests = AtomicInteger()
        server.createContext("/3d/light/7767.webp") { exchange ->
            requests.incrementAndGet()
            val bytes = (if (status.get() == 200) "valid cover" else "outage").toByteArray()
            exchange.responseHeaders.add("Content-Type", "image/webp")
            exchange.sendResponseHeaders(status.get(), bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val cache = FlycastCoverCache(root) { it.isFile && it.length() > 0 }
            val disc = cache.storeOffline("T1401N") { it.writeText("disc artwork") }
            assertEquals(disc, cache.find(listOf("T1401N"), style = 2, isLight = true))
            assertEquals(0, requests.get())
            val downloader = FlycastImageDownloader(root) { it.isFile && it.readText() == "valid cover" }
            val url = "http://127.0.0.1:${server.address.port}/3d/light/7767.webp"
            try {
                downloader.download(url, "T1401N")
                fail("Temporary outage must remain retryable")
            } catch (_: BoxartRetryException) {
                assertEquals(disc, cache.find(listOf("T1401N"), style = 2, isLight = true))
                assertFalse(cache.isOnline(disc!!))
                assertFalse(cache.missFile("T1401N").exists())
            }
            status.set(200)
            val downloaded = downloader.download(url, "T1401N")
            assertEquals(downloaded, cache.find(listOf("T1401N"), style = 2, isLight = true)?.absolutePath)
            assertEquals(disc, cache.find(listOf("T1401N"), style = 2, isLight = false))
            assertEquals("disc artwork", disc!!.readText())
            assertFalse(cache.isOnline(disc))
            assertEquals(2, requests.get())
            assertFalse(root.listFiles().orEmpty().any { it.extension == "tmp" })
        } finally {
            server.stop(0)
            root.deleteRecursively()
        }
    }
}
