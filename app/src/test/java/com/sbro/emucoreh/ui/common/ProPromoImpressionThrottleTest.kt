package com.sbro.emucoreh.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProPromoImpressionThrottleTest {
    private val t0 = 5_000_000L

    @Before
    fun setUp() = ProPromoImpressionThrottle.reset()

    @Test
    fun firstImpressionLogs_repeatWithinWindowDoesNot() {
        assertTrue(ProPromoImpressionThrottle.shouldLog("library", t0))
        assertFalse(ProPromoImpressionThrottle.shouldLog("library", t0 + 1))
        assertFalse(ProPromoImpressionThrottle.shouldLog("library", t0 + ProPromoImpressionThrottle.INTERVAL_MS - 1))
    }

    @Test
    fun logsAgainAfterWindow() {
        assertTrue(ProPromoImpressionThrottle.shouldLog("themes", t0))
        assertTrue(ProPromoImpressionThrottle.shouldLog("themes", t0 + ProPromoImpressionThrottle.INTERVAL_MS))
    }

    @Test
    fun placementsAreIndependent() {
        assertTrue(ProPromoImpressionThrottle.shouldLog("library", t0))
        assertTrue(ProPromoImpressionThrottle.shouldLog("controls", t0 + 1))
    }
}
