package com.sbro.emucoreh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GamepadBindingRulesTest {
    @Test fun arcadeAssignmentPreservesOrdinaryMappingButReplacesArcadeDuplicate() {
        val result = GamepadBindingRules.assign(mapOf("cross" to 96, "coin" to 96), "gun_trigger", 96)
        assertEquals(mapOf("cross" to 96, "gun_trigger" to 96), result)
    }
    @Test fun testSwitchUsesSameArcadeConflictRules() {
        assertEquals(mapOf("l3" to 106, "test" to 106),
            GamepadBindingRules.assign(mapOf("l3" to 106, "gun_reload" to 106), "test", 106))
    }
    @Test fun perGameMergeAppliesContextConflictsWithoutDroppingOtherPorts() {
        assertEquals(mapOf(0 to mapOf("cross" to 96, "gun_trigger" to 96), 1 to mapOf("start" to 108)),
            GamepadBindingRules.merge(mapOf(0 to mapOf("cross" to 96, "coin" to 96), 1 to mapOf("start" to 108)),
                mapOf(0 to mapOf("gun_trigger" to 96))))
    }
}
