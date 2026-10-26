package com.sbro.emucoreh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BiosImportHistoryTest {
    private val markers = mutableMapOf<String, String>()
    private val history = BiosImportHistory(markers::get) { key, value -> markers[key] = value }

    @Test fun `onboarding import is not repeated by either onboarding or home collectors`() {
        history.recordImported("content://bios/selected")
        var reads = 0
        repeat(3) {
            history.restore(listOf("content://bios/selected")) { reads++; true }
        }
        assertEquals(0, reads)
    }

    @Test fun `adding a folder only scans the new selection`() {
        val reads = mutableListOf<String>()
        history.restore(listOf("old")) { reads += it; true }
        history.restore(listOf("old", "new", "old", "")) { reads += it; true }
        history.restore(listOf("new", "old")) { reads += it; true }
        assertEquals(listOf("old", "new"), reads)
    }

    @Test fun `unavailable or failed import retries without rereading successful folders`() {
        val reads = mutableListOf<String>()
        history.restore(listOf("available", "offline")) { reads += it; it != "offline" }
        history.restore(listOf("available", "offline")) { reads += it; error("provider died") }
        history.restore(listOf("available", "offline")) { reads += it; true }
        history.restore(listOf("available", "offline")) { reads += it; true }
        assertEquals(listOf("available", "offline", "offline", "offline"), reads)
    }

    @Test fun `explicit refresh still imports all folders`() {
        history.recordImported("one")
        val reads = mutableListOf<String>()
        history.restore(listOf("one", "two"), force = true) { reads += it; true }
        assertEquals(listOf("one", "two"), reads)
    }

    @Test fun `previous completed migration remains valid across upgrade`() {
        markers["completed"] = BiosImportHistory.selectionKey(listOf("old", "second"))
        var reads = 0
        history.restore(listOf("second", "old")) { reads++; true }
        assertEquals(0, reads)
    }

    @Test fun `failed forced refresh is retried on the next ordinary startup`() {
        history.restore(listOf("one", "two")) { true }
        history.restore(listOf("one", "two"), force = true) { it == "one" }
        val reads = mutableListOf<String>()
        history.restore(listOf("one", "two")) { reads += it; true }
        assertEquals(listOf("two"), reads)
    }
}
