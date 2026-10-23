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

    @Test fun model3NeverSharesDreamcastTitleCacheAndAliasesUseOnePlatformScope() {
        assertNotEquals(scopedCoverCacheKey("sega rally 2", "DREAMCAST"), scopedCoverCacheKey("sega rally 2", "MODEL_3"))
        assertEquals(scopedCoverCacheKey("vf3", "MODEL_3"), scopedCoverCacheKey("vf3", "Sega Model 3"))
        assertEquals("Sega Model 3", com.sbro.emucoreh.data.igdb.catalogPlatformName("MODEL3"))
    }

    @Test fun previouslyUnscopedModel3ArtworkCannotSurviveThePlatformMigration() {
        assertFalse(coverCacheFileMatchesPlatform("SRALLY2.dark.online.webp", "MODEL_3"))
        assertTrue(coverCacheFileMatchesPlatform("sega_sega_model_3_SRALLY2.dark.online.webp", "MODEL_3"))
        assertTrue(coverCacheFileMatchesPlatform("SRALLY2.dark.online.webp", "DREAMCAST"))
    }
}
