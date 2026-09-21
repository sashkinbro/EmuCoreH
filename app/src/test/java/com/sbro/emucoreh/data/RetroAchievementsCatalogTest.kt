package com.sbro.emucoreh.data

import com.sbro.emucoreh.data.RetroAchievementsCatalog.parseAccountProgress
import com.sbro.emucoreh.data.RetroAchievementsCatalog.parseGameTitles
import com.sbro.emucoreh.data.RetroAchievementsCatalog.titleKey
import org.junit.Assert.*
import org.junit.Test

class RetroAchievementsCatalogTest {
    @Test fun requestsDreamcastConsole() {
        assertEquals(40, RetroAchievementsCatalog.DREAMCAST_CONSOLE_ID)
    }

    @Test fun parsesEntireCatalogIncludingUnplayedGames() {
        val entries = (1..251).joinToString(",") { "\"$it\":{\"Achievements\":12}" }
        val games = "{\"Success\":true,\"Response\":{$entries}}".parseAccountProgress()
        assertEquals(251, games.size)
        assertEquals(0, games.getValue(251).earned)
    }

    @Test fun preservesAccountProgressAndIgnoresEmptySets() {
        val games = """{"Success":true,"Response":{"3537":{"Achievements":36,"Unlocked":5,"UnlockedHardcore":3},"3538":{"Achievements":0}}}""".parseAccountProgress()
        assertEquals(1, games.size)
        assertEquals(5, games.getValue(3537).earned)
        assertEquals(3, games.getValue(3537).hardcore)
    }

    @Test fun acceptsEmptyServerArray() {
        assertTrue("""{"Success":true,"Response":[]}""".parseAccountProgress().isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun failedRequestIsNotAnEmptyLibrary() {
        """{"Success":false,"Error":"Invalid token"}""".parseAccountProgress()
    }

    @Test(expected = IllegalStateException::class)
    fun missingResponseIsNotAnEmptyLibrary() {
        """{"Success":true}""".parseAccountProgress()
    }

    @Test fun acceptsTitleArrayAndKeyedObject() {
        val array = """{"Success":true,"Response":[{"ID":3537,"Title":"Chains of Olympus","ImageIcon":"/Images/000001.png"}]}""".parseGameTitles()
        val keyed = """{"Success":true,"Response":{"3537":{"Title":"Chains of Olympus","ImageIcon":"/Images/000001.png"}}}""".parseGameTitles()
        assertEquals(array, keyed)
        assertEquals("https://media.retroachievements.org/Images/000001.png", array.getValue(3537).second)
    }

    @Test fun matchesDiscFilenamesWithoutRemovingTitleWordsAfterPeriods() {
        assertEquals(titleKey("God of War: Ghost of Sparta"), titleKey("God of War - Ghost of Sparta (Europe) (En,Pl,Ru).iso"))
        assertEquals(titleKey("WWE SmackDown vs. Raw 2011"), titleKey("WWE SmackDown vs. Raw 2011 (USA).iso"))
        assertNotEquals(titleKey("WWE SmackDown vs. Raw 2010"), titleKey("WWE SmackDown vs. Raw 2011"))
    }
    @Test fun matchesPublisherAndSubtitleVariantsWithoutMergingSubsets() {
        assertTrue(RetroAchievementsCatalog.titleKeys("Disney-Pixar Toy Story 3").intersect(RetroAchievementsCatalog.titleKeys("Toy Story 3")).isNotEmpty())
        assertTrue(RetroAchievementsCatalog.titleKeys("Gran Turismo").intersect(RetroAchievementsCatalog.titleKeys("Gran Turismo: The Real Driving Simulator")).isNotEmpty())
        assertNotEquals(titleKey("Tekken 6"), titleKey("Tekken 6 [Subset - Bonus]"))
    }

}
