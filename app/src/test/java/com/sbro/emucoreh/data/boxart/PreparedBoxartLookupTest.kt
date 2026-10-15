package com.sbro.emucoreh.data.boxart

import org.junit.Assert.*
import org.junit.Test

class PreparedBoxartLookupTest {
    @Test fun `catalog UID omitted from batch can fall back after repository artwork is missing`() {
        val lookup = PreparedBoxartLookup()
        val info = TheGamesDbClient.GameInfo("SoulCalibur", null, null, "https://covers/soul.jpg")
        lookup.begin()
        lookup.skipForCatalog("T1401N")
        assertEquals(info, runCatching { lookup.find("T1401N", 2_000L) { info } }.getOrNull())
    }

    @Test fun `catalog fallback result is reused without repeated individual requests`() {
        val lookup = PreparedBoxartLookup()
        lookup.begin()
        lookup.skipForCatalog("T1401N")
        var requests = 0
        repeat(2) { runCatching { lookup.find("T1401N", 2_000L) { requests++; null } } }
        assertEquals(1, requests)
    }

    @Test fun `catalog fallback failure retains the retry instead of caching a missing game`() {
        val lookup = PreparedBoxartLookup()
        lookup.begin()
        lookup.skipForCatalog("T1401N")
        val first = runCatching {
            lookup.find("T1401N", 2_000L) { throw BoxartRetryException(3_000L) }
        }.exceptionOrNull() as BoxartRetryException
        assertEquals(3_000L, first.retryAtMillis)
        val info = TheGamesDbClient.GameInfo("SoulCalibur", null, null, "https://covers/soul.jpg")
        assertEquals(info, lookup.find("T1401N", 4_000L) { info })
    }

    @Test fun `catalog skip does not enable individual requests for a failed batch UID`() {
        val lookup = PreparedBoxartLookup()
        lookup.begin()
        lookup.skipForCatalog("T1401N")
        var requests = 0
        val failure = runCatching { lookup.find("OTHER", 2_000L) { requests++; null } }
            .exceptionOrNull() as BoxartRetryException
        assertEquals(2_000L, failure.retryAtMillis)
        assertEquals(0, requests)
    }

    @Test fun `failed batch never expands into an individual UID request`() {
        val lookup = PreparedBoxartLookup()
        lookup.begin()
        var individualRequests = 0
        try {
            lookup.find("MK-5103750", 2_000L) { individualRequests++; null }
            fail("Unprepared UIDs must wait for another batch")
        } catch (failure: BoxartRetryException) {
            assertEquals(2_000L, failure.retryAtMillis)
        }
        assertEquals(0, individualRequests)
    }

    @Test fun `completed batches remain usable after a later batch failed`() {
        val lookup = PreparedBoxartLookup()
        val info = TheGamesDbClient.GameInfo("Daytona USA", null, null, "https://covers/front.jpg")
        lookup.begin()
        lookup.add(mapOf("MK-51037" to info, "UNKNOWN" to null))
        assertEquals(info, lookup.find("MK-51037", 2_000L) { error("Unexpected individual request") })
        assertNull(lookup.find("UNKNOWN", 2_000L) { error("Unexpected individual request") })
    }

    @Test fun `standalone lookup can still use the individual endpoint`() {
        val lookup = PreparedBoxartLookup()
        val info = TheGamesDbClient.GameInfo("Daytona USA", null, null, "https://covers/front.jpg")
        assertEquals(info, lookup.find("MK-51037", 0L) { info })
    }
}
