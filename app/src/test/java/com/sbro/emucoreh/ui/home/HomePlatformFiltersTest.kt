package com.sbro.emucoreh.ui.home

import com.sbro.emucoreh.data.LibraryPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePlatformFiltersTest {
    private val dreamcast = LibraryPlatform.DREAMCAST
    private val naomi = LibraryPlatform.NAOMI
    private val atomiswave = LibraryPlatform.ATOMISWAVE

    @Test fun `platform chips toggle independently without mutating the previous selection`() {
        val initial = setOf(dreamcast)
        val both = toggleHomePlatformFilter(initial, naomi)
        assertEquals(setOf(dreamcast, naomi), both)
        assertEquals(setOf(dreamcast), initial)
        assertEquals(setOf(naomi), toggleHomePlatformFilter(both, dreamcast))
        assertEquals(emptySet<LibraryPlatform>(), toggleHomePlatformFilter(setOf(naomi), naomi))
    }

    @Test fun `all clears every selected platform`() {
        assertEquals(emptySet<LibraryPlatform>(), toggleHomePlatformFilter(setOf(dreamcast, naomi), null))
    }

    @Test fun `empty selection includes every platform`() {
        LibraryPlatform.entries.forEach { assertTrue(matchesHomePlatformFilters(emptySet(), it)) }
    }

    @Test fun `multiple selections include either platform and exclude unselected games`() {
        val filters = setOf(dreamcast, naomi)
        assertTrue(matchesHomePlatformFilters(filters, dreamcast))
        assertTrue(matchesHomePlatformFilters(filters, naomi))
        assertFalse(matchesHomePlatformFilters(filters, atomiswave))
    }

    @Test fun `library changes remove stale selections while keeping available selections`() {
        assertEquals(setOf(naomi), pruneHomePlatformFilters(setOf(dreamcast, naomi), setOf(naomi, atomiswave)))
    }

    @Test fun `removing every selected platform returns to all`() {
        val filters = pruneHomePlatformFilters(setOf(dreamcast), setOf(naomi))
        assertTrue(filters.isEmpty())
        assertTrue(matchesHomePlatformFilters(filters, naomi))
        assertTrue(pruneHomePlatformFilters(setOf(dreamcast), emptySet()).isEmpty())
    }
}
