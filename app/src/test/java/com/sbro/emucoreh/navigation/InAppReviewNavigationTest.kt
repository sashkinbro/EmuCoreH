package com.sbro.emucoreh.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppReviewNavigationTest {
    @Test
    fun onlyNormalLibraryGameSessionsCanContributeToReviewEligibility() {
        assertTrue(EmulationRoute(gamePath = "content://games/example.iso").isMeaningfulReviewSession())
        assertFalse(EmulationRoute(gamePath = null).isMeaningfulReviewSession())
        assertFalse(EmulationRoute(gamePath = "").isMeaningfulReviewSession())
        assertFalse(EmulationRoute(gamePath = "game.iso", bootBios = true).isMeaningfulReviewSession())
        assertFalse(EmulationRoute(gamePath = "game.iso", bootSmokeProbe = true).isMeaningfulReviewSession())
        assertFalse(EmulationRoute(gamePath = "game.iso", autotestMode = true).isMeaningfulReviewSession())
        assertFalse(EmulationRoute(gamePath = "game.iso", exitAppOnExit = true).isMeaningfulReviewSession())
    }
}
