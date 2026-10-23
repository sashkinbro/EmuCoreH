package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class SegaControlProfilesTest {
    @Test fun genesisHasSixButtonsAndModeButNoAnalogOrArcadeControls() {
        val defaults = SegaControlProfiles.defaults(EmulationPlatform.GENESIS)
        assertTrue(defaults.keys.containsAll(listOf("cross", "circle", "square", "triangle", "l1", "r1", "select", "start")))
        assertFalse(SegaControlProfiles.supports(EmulationPlatform.GENESIS, "l2"))
        assertFalse(SegaControlProfiles.supports(EmulationPlatform.GENESIS, "coin"))
        assertFalse(SegaControlProfiles.supports(EmulationPlatform.GENESIS, "right_stick_up"))
    }
    @Test fun handheldAndEightBitPadsOnlyHaveTheirPhysicalButtons() {
        for (platform in listOf(EmulationPlatform.MASTER_SYSTEM, EmulationPlatform.GAME_GEAR, EmulationPlatform.SG_1000)) {
            val defaults = SegaControlProfiles.defaults(platform)
            assertTrue(defaults.keys.containsAll(listOf("cross", "circle")))
            assertFalse(defaults.containsKey("square"))
            assertFalse(defaults.containsKey("select"))
        }
        assertTrue(SegaControlProfiles.supports(EmulationPlatform.MASTER_SYSTEM, "start"))
        assertTrue(SegaControlProfiles.supports(EmulationPlatform.GAME_GEAR, "start"))
        assertFalse(SegaControlProfiles.supports(EmulationPlatform.SG_1000, "start"))
    }
    @Test fun customBindingsOverrideDefaultsWithoutInheritingAnotherPlatform() {
        val genesis = SegaControlProfiles.resolve(EmulationPlatform.GENESIS, mapOf(0 to mapOf("cross" to 103)))
        assertEquals(103, genesis.getValue(0)["cross"])
        assertFalse(genesis.getValue(0).containsKey("r1"))
        assertEquals(96, SegaControlProfiles.resolve(EmulationPlatform.GAME_GEAR, emptyMap()).getValue(0)["cross"])
        assertEquals(96, genesis.getValue(1)["cross"])
    }
    @Test fun leftStickAndHatMergeWithoutReleasingHeldDirections() {
        assertEquals(setOf(21, 19), segaDpadDirections(-1f, -1f, 0f, 0f))
        val heldByBoth = segaDpadDirections(-1f, 0f, -1f, 0f)
        val heldByHat = segaDpadDirections(0f, 0f, -1f, 0f)
        assertTrue((heldByBoth - heldByHat).isEmpty())
        assertEquals(setOf(21), heldByHat)
        assertEquals(emptySet<Int>(), segaDpadDirections(0f, 0f, 0f, 0f))
    }
    @Test fun processedStickThresholdIgnoresDriftAndPreservesDiagonals() {
        assertTrue(segaDpadDirections(0.5f, -0.5f, 0f, 0f).isEmpty())
        assertEquals(setOf(22, 20), segaDpadDirections(0.51f, 0.51f, 0f, 0f))
    }
}
