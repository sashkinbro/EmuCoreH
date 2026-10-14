package com.sbro.emucoreh.data.boxart

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.Assert.*
import org.junit.Test

class TheGamesDbTest {
    private fun withApi(block: (TheGamesDbClient, MutableList<String>, (String, Int, String) -> Unit) -> Unit) {
        val requests = mutableListOf<String>()
        val replies = mutableMapOf<String, Pair<Int, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests += exchange.requestURI.toString()
            val reply = replies[path] ?: (404 to """{"code":404,"status":"No results"}""")
            val bytes = reply.second.toByteArray()
            exchange.sendResponseHeaders(reply.first, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        replies["/Platforms/ByPlatformName"] = 200 to """{"code":200,"data":{"platforms":[{"id":16,"name":"Sega Dreamcast"},{"id":23,"name":"Arcade"}]}}"""
        server.start()
        try {
            block(TheGamesDbClient("http://127.0.0.1:${server.address.port}/"), requests) { path, code, body ->
                replies[path] = code to body
            }
        } finally {
            server.stop(0)
        }
    }

    @Test fun `unknown UID does not pause the name fallback or other games`() = withApi { client, _, reply ->
        reply("/Games/ByGameName", 200, gameResponse("MK-51037"))
        assertNull(client.findByUniqueId("UNKNOWN"))
        assertEquals("https://covers/boxart/front/7766.jpg", client.findByName("Daytona USA")?.imageUrl)
    }

    @Test fun `arcade name fallback searches arcade platform`() = withApi { client, requests, reply ->
        reply("/Games/ByGameName", 200, gameResponse(""))
        assertNotNull(client.findByName("Virtua Tennis", arcade = true))
        assertTrue(requests.last().contains("filter%5Bplatform%5D=23"))
    }

    @Test fun `temporary API failure is retryable rather than a missing game`() = withApi { client, _, reply ->
        reply("/Games/ByGameUniqueID", 503, """{"code":503}""")
        try {
            client.findByUniqueId("MK-51037")
            fail("A failed lookup must retain the online retry")
        } catch (failure: BoxartRetryException) {
            assertTrue(failure.retryAtMillis > System.currentTimeMillis())
        }
    }

    @Test fun `API allowance response preserves a long retry delay`() = withApi { client, _, reply ->
        reply("/Games/ByGameUniqueID", 429, """{"code":429}""")
        try {
            client.findByUniqueId("MK-51037")
            fail("Rate limit must be retryable")
        } catch (failure: BoxartRetryException) {
            assertTrue(failure.retryAtMillis - System.currentTimeMillis() > 23 * 60 * 60 * 1000L)
        }
    }

    @Test fun `batch UID lookup associates artwork by exact product number`() = withApi { client, requests, reply ->
        reply("/Games/ByGameUniqueID", 200, gameResponse("MK-5103750"))
        val result = client.findByUniqueIds(listOf("MK-51037", "MK-5103750", "MK-5103750"))
        assertNull(result["MK-51037"])
        assertEquals("https://covers/boxart/front/7766.jpg", result["MK-5103750"]?.imageUrl)
        assertEquals(1, requests.count { it.startsWith("/Games/ByGameUniqueID") })
        assertTrue(requests.last().contains("uid=MK-51037%2CMK-5103750"))
    }

    @Test fun `null image filenames do not shadow a valid front cover`() = withApi { client, _, reply ->
        reply("/Games/ByGameName", 200, gameResponse("MK-51037", """{"type":"boxart","filename":null},"""))
        assertEquals("https://covers/boxart/front/7766.jpg", client.findByName("Daytona USA")?.imageUrl)
    }

    @Test fun `successful HTTP response with an API no-results code still permits fallback`() = withApi { client, _, reply ->
        reply("/Games/ByGameUniqueID", 200, """{"code":404,"status":"No results"}""")
        reply("/Games/ByGameName", 200, gameResponse("MK-51037"))
        assertNull(client.findByUniqueId("UNKNOWN"))
        assertNotNull(client.findByName("Daytona USA"))
    }

    @Test fun `batch no-results is recorded for every UID without redundant individual requests`() = withApi { client, requests, _ ->
        val result = client.findByUniqueIds(listOf("UNKNOWN1", "UNKNOWN2"))
        assertEquals(setOf("UNKNOWN1", "UNKNOWN2"), result.keys)
        assertTrue(result.values.all { it == null })
        assertEquals(1, requests.count { it.startsWith("/Games/ByGameUniqueID") })
    }

    @Test fun `large libraries split UID requests into bounded batches`() = withApi { client, requests, _ ->
        val result = client.findByUniqueIds((1..23).map { "UID$it" })
        assertEquals(23, result.size)
        assertEquals(3, requests.count { it.startsWith("/Games/ByGameUniqueID") })
    }

    @Test fun `incomplete API response is retryable rather than a definitive miss`() = withApi { client, _, reply ->
        reply("/Games/ByGameUniqueID", 200, """{"code":200,"data":{}}""")
        try {
            client.findByUniqueId("MK-51037")
            fail("Incomplete API response must not create a negative cover cache")
        } catch (failure: BoxartRetryException) {
            assertTrue(failure.retryAtMillis > System.currentTimeMillis())
        }
    }

    private fun gameResponse(uid: String, extraImage: String = "") = """{
        "code":200,"data":{"games":[{"id":7766,"game_title":"Daytona USA","uids":[{"uid":"$uid"}]}]},
        "include":{"boxart":{"base_url":{"thumb":"https://covers/"},"data":{"7766":[
            $extraImage
            {"type":"boxart","side":"back","filename":"boxart/back/7766.jpg"},
            {"type":"boxart","side":"front","filename":"boxart/front/7766.jpg"}
        ]}}}
    }"""
}
