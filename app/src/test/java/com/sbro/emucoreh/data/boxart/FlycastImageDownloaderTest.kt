package com.sbro.emucoreh.data.boxart

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
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
        assertFalse(File(root, "tekken.miss-v2").exists())
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
}
