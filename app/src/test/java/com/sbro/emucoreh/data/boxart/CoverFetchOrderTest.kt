package com.sbro.emucoreh.data.boxart

import org.junit.Assert.*
import org.junit.Test

class CoverFetchOrderTest {
    @Test fun `repository hit never opens the disc or calls fallback services`() {
        assertEquals("full cover", CoverFetchOrder.resolve(CoverFetchMode.FULL, null,
            repository = { "full cover" }, disc = { error("Disc must not open") },
            fallback = { error("No metadata or external API needed") }, onRetry = { throw it }))
    }

    @Test fun `fast repository miss returns without reading disc metadata or artwork`() {
        assertNull(CoverFetchOrder.resolve<String>(CoverFetchMode.REPOSITORY_ONLY, null,
            repository = { null }, disc = { error("Fast pass must not open any disc") },
            fallback = { error("Fast pass must not query fallback services") }, onRetry = { throw it }))
    }

    @Test fun `offline launch extracts artwork without making an HTTP request`() {
        assertEquals("disc label", CoverFetchOrder.resolve(CoverFetchMode.OFFLINE_ONLY, null,
            repository = { error("No network") }, disc = { "disc label" },
            fallback = { error("No network") }, onRetry = { throw it }))
    }

    @Test fun `repository outage falls back to disc and preserves online retry`() {
        var retryAt = 0L
        assertEquals("disc label", CoverFetchOrder.resolve(CoverFetchMode.FULL, null,
            repository = { throw BoxartRetryException(1234L) }, disc = { "disc label" },
            fallback = { error("Do not repeat network calls after an outage") }, onRetry = { retryAt = it.retryAtMillis }))
        assertEquals(1234L, retryAt)
    }

    @Test fun `cached disc artwork remains visible while a repository retry is postponed`() {
        var retryAt = 0L
        assertEquals("cached disc", CoverFetchOrder.resolve(CoverFetchMode.REPOSITORY_ONLY, "cached disc",
            repository = { throw BoxartRetryException(1234L) }, disc = { error("Already cached") },
            fallback = { error("Fast pass") }, onRetry = { retryAt = it.retryAtMillis }))
        assertEquals(1234L, retryAt)
    }

    @Test fun `unmapped game can use metadata based fallback after a definitive repository miss`() {
        val stages = mutableListOf<String>()
        assertEquals("fallback cover", CoverFetchOrder.resolve(CoverFetchMode.FULL, null,
            repository = { stages += "repository"; null },
            disc = { stages += "disc"; "label" },
            fallback = { label -> assertEquals("label", label); stages += "fallback"; "fallback cover" },
            onRetry = { throw it }))
        assertEquals(listOf("repository", "disc", "fallback"), stages)
    }

    @Test fun `fallback network failure retains local artwork and remains retryable`() {
        var retryAt = 0L
        assertEquals("disc label", CoverFetchOrder.resolve(CoverFetchMode.FULL, "disc label",
            repository = { null }, disc = { error("Already cached") },
            fallback = { throw BoxartRetryException(5678L) }, onRetry = { retryAt = it.retryAtMillis }))
        assertEquals(5678L, retryAt)
    }
}
