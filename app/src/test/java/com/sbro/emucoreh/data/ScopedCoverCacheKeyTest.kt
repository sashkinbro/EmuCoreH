package com.sbro.emucoreh.data

import org.junit.Assert.*
import org.junit.Test

class ScopedCoverCacheKeyTest {
    @Test fun sameTitleHasIndependentSegaCacheFilesAndLegacyDreamcastKeysStayStable() {
        assertEquals("sonic", scopedCoverCacheKey("sonic", null))
        assertEquals("sonic", scopedCoverCacheKey("sonic", "DREAMCAST"))
        assertNotEquals(scopedCoverCacheKey("sonic", "GENESIS"), scopedCoverCacheKey("sonic", "MASTER_SYSTEM"))
        assertEquals(scopedCoverCacheKey("sonic", "SEGA_CD"), scopedCoverCacheKey("sonic", "Sega CD"))
    }
}
