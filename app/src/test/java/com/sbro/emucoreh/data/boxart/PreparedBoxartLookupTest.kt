package com.sbro.emucoreh.data.boxart

import org.junit.Assert.*
import org.junit.Test

class PreparedBoxartLookupTest {
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
