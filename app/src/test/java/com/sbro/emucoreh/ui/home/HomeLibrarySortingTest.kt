package com.sbro.emucoreh.ui.home

import com.sbro.emucoreh.data.GameItem
import com.sbro.emucoreh.data.LibraryPlatform
import org.junit.Assert.*
import org.junit.Test

class HomeLibrarySortingTest {
    private fun game(title: String, name: String = "$title.chd", size: Long = 1, discSize: Long? = null) =
        GameItem(title, "/games/$name", name, size, 0, discSize = discSize)

    @Test fun `platform mode separates all systems and alphabetizes within each system`() {
        val games = listOf(game("Arcade"), game("Zebra"), game("Naomi 2"), game("System SP"), game("Atomiswave"), game("Éclair"), game("Naomi"))
        val platforms = mapOf("Arcade" to LibraryPlatform.ARCADE, "Zebra" to LibraryPlatform.DREAMCAST,
            "Naomi 2" to LibraryPlatform.NAOMI_2, "System SP" to LibraryPlatform.SYSTEM_SP,
            "Atomiswave" to LibraryPlatform.ATOMISWAVE, "Éclair" to LibraryPlatform.DREAMCAST, "Naomi" to LibraryPlatform.NAOMI)
        val sections = buildHomeLibrarySections(games, HomeSortOption.PLATFORM) { platforms.getValue(it.title) }
        assertEquals(listOf(LibraryPlatform.DREAMCAST, LibraryPlatform.NAOMI, LibraryPlatform.NAOMI_2,
            LibraryPlatform.ATOMISWAVE, LibraryPlatform.SYSTEM_SP, LibraryPlatform.ARCADE), sections.map { it.platform })
        assertEquals(listOf("Éclair", "Zebra"), sections.first().games.map { it.title })
        assertEquals(games.toSet(), sections.flatMap { it.games }.toSet())
        assertEquals(games.size, sections.sumOf { it.games.size })
    }

    @Test fun `filtered library omits empty platforms and retains game objects`() {
        val visible = game("Metal Slug 6", "mslug6.zip")
        val sections = buildHomeLibrarySections(listOf(visible), HomeSortOption.PLATFORM) { LibraryPlatform.ATOMISWAVE }
        assertEquals(listOf(LibraryPlatform.ATOMISWAVE), sections.map { it.platform })
        assertSame(visible, sections.single().games.single())
        assertTrue(buildHomeLibrarySections(emptyList(), HomeSortOption.PLATFORM) { error("No games") }.isEmpty())
    }

    @Test fun `ordinary sorts have no platform headers or catalog queries`() {
        val games = listOf(game("Zebra"), game("alpha"), game("Éclair"))
        val ascending = buildHomeLibrarySections(games, HomeSortOption.TITLE_ASC) { error("Unnecessary platform lookup") }
        assertNull(ascending.single().platform)
        assertEquals(listOf("alpha", "Éclair", "Zebra"), ascending.single().games.map { it.title })
        val descending = buildHomeLibrarySections(games, HomeSortOption.TITLE_DESC) { error("Unnecessary platform lookup") }
        assertEquals(listOf("Zebra", "Éclair", "alpha"), descending.single().games.map { it.title })
    }

    @Test fun `size sort still uses complete multi track size`() {
        val games = listOf(game("Small", size = 10), game("Large disc", size = 1, discSize = 100))
        val sections = buildHomeLibrarySections(games, HomeSortOption.SIZE_DESC) { error("Unnecessary platform lookup") }
        assertEquals(listOf("Large disc", "Small"), sections.single().games.map { it.title })
    }

    @Test fun `equal platform titles have deterministic filename ordering`() {
        val games = listOf(game("Soul", "b.chd"), game("Soul", "a.chd"))
        val sections = buildHomeLibrarySections(games, HomeSortOption.PLATFORM) { LibraryPlatform.DREAMCAST }
        assertEquals(listOf("a.chd", "b.chd"), sections.single().games.map { it.fileName })
    }

    @Test fun `saved sorts round trip and unavailable preferences use the default`() {
        HomeSortOption.entries.forEach { assertEquals(it, HomeSortOption.fromPreference(it.name)) }
        assertEquals(HomeSortOption.TITLE_ASC, HomeSortOption.fromPreference(null))
        assertEquals(HomeSortOption.TITLE_ASC, HomeSortOption.fromPreference("FUTURE_OPTION"))
    }
}
