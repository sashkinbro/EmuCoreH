package com.sbro.emucoreh.ui.home

import androidx.compose.ui.focus.FocusRequester
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HomeLibraryFocusRegistryTest {
    @Test fun ignoresHeadersAndPrefetchedRowsAndChoosesTopmostVisibleGame() {
        val registry = HomeLibraryFocusRegistry()
        val recent = FocusRequester()
        val first = FocusRequester()
        val second = FocusRequester()
        registry.register("recent", recent)
        registry.register("row1", first)
        registry.register("row2", second)
        assertSame(first, registry.visibleRequester(listOf("header", "row1", "row2")))
        assertSame(second, registry.visibleRequester(listOf("row2")))
        assertNull(registry.visibleRequester(listOf("header")))
    }

    @Test fun disposingAnOldRowDoesNotRemoveItsReplacement() {
        val registry = HomeLibraryFocusRegistry()
        val old = FocusRequester()
        val replacement = FocusRequester()
        registry.register("row", old)
        registry.register("row", replacement)
        registry.unregister("row", old)
        assertSame(replacement, registry.visibleRequester(listOf("row")))
        registry.unregister("row", replacement)
        assertNull(registry.visibleRequester(listOf("row")))
    }
}
