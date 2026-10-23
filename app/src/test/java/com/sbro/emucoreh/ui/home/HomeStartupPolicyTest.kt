package com.sbro.emucoreh.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStartupPolicyTest {
    @Test fun `cached SAF roots can appear before document traversal when all read grants remain`() {
        assertTrue(canPublishHomeLibraryCache(listOf("content://games/a", "content://games/b"),
            setOf("content://games/a", "content://games/b")))
    }

    @Test fun `revoked read grant requires normal validation rather than publishing stale cached roots`() {
        assertFalse(canPublishHomeLibraryCache(listOf("content://games/a", "content://games/b"),
            setOf("content://games/a")))
    }

    @Test fun `local cached roots can appear while ordinary filesystem validation runs`() {
        assertTrue(canPublishHomeLibraryCache(listOf("/games"), emptySet()))
        assertFalse(canPublishHomeLibraryCache(emptyList(), emptySet()))
    }
}
