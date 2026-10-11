package com.sbro.emucoreh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GamepadManagerTest {
    @Test
    fun gunBindingsWinOnlyForTheGunPort() {
        val bindings = linkedMapOf("cross" to 96, "gun_trigger" to 96, "gun_reload" to 104)
        GamepadManager.setLightGunPorts(setOf(1), aimSource = 1)
        try {
            assertEquals("cross", GamepadManager.resolveMappedActionIdForKeyCode(96, bindings, padIndex = 0))
            assertEquals("gun_trigger", GamepadManager.resolveMappedActionIdForKeyCode(96, bindings, padIndex = 1))
            assertEquals("l2", GamepadManager.resolveMappedActionIdForTriggerAxis("l2", bindings, padIndex = 0))
            assertEquals("gun_reload", GamepadManager.resolveMappedActionIdForTriggerAxis("l2", bindings, padIndex = 1))
        } finally {
            GamepadManager.setLightGunPorts(emptySet())
        }
    }

    @Test
    fun arcadeSwitchesAreActiveInArcadeButGunBindingsStillRequireGunPort() {
        GamepadManager.setArcadeModeActive(true)
        try {
            assertEquals("coin", GamepadManager.resolveMappedActionIdForKeyCode(96, mapOf("cross" to 96, "coin" to 96)))
            assertEquals("test", GamepadManager.resolveMappedActionIdForKeyCode(106, mapOf("test" to 106)))
            assertEquals("service", GamepadManager.resolveMappedActionIdForKeyCode(107, mapOf("service" to 107)))
            assertEquals("cross", GamepadManager.resolveMappedActionIdForKeyCode(96, mapOf("gun_trigger" to 96)))
        } finally {
            GamepadManager.setArcadeModeActive(false)
        }
    }

    @Test
    fun namedCabinetActionsRouteToIndependentCabinetKeys() {
        val actions = GamepadManager.mappableButtonActions().associateBy { it.id }
        assertEquals(139, actions.getValue("coin").padKey)
        assertEquals(140, actions.getValue("test").padKey)
        assertEquals(141, actions.getValue("service").padKey)
        assertEquals(109, actions.getValue("select").padKey)
    }

    @Test
    fun sharedCabinetBindingsRestoreOrdinarySelectAndStickClicksOutsideArcade() {
        val bindings = mapOf("select" to 109, "coin" to 109, "l3" to 106, "test" to 106,
            "r3" to 107, "service" to 107)
        assertEquals("select", GamepadManager.resolveMappedActionIdForKeyCode(109, bindings))
        assertEquals("l3", GamepadManager.resolveMappedActionIdForKeyCode(106, bindings))
        assertEquals("r3", GamepadManager.resolveMappedActionIdForKeyCode(107, bindings))
        GamepadManager.setArcadeModeActive(true)
        try {
            assertEquals("coin", GamepadManager.resolveMappedActionIdForKeyCode(109, bindings))
            assertEquals("test", GamepadManager.resolveMappedActionIdForKeyCode(106, bindings))
            assertEquals("service", GamepadManager.resolveMappedActionIdForKeyCode(107, bindings))
        } finally {
            GamepadManager.setArcadeModeActive(false)
        }
    }

    @Test
    fun cabinetDefaultsWinOnlyInArcadeContextAndKeepStartUnchanged() {
        assertEquals("select", GamepadManager.resolveMappedActionIdForKeyCode(109, emptyMap()))
        assertEquals("l3", GamepadManager.resolveMappedActionIdForKeyCode(106, emptyMap()))
        assertEquals("r3", GamepadManager.resolveMappedActionIdForKeyCode(107, emptyMap()))
        assertEquals("start", GamepadManager.resolveMappedActionIdForKeyCode(108, emptyMap()))
        org.junit.Assert.assertNull(GamepadManager.resolveMappedActionIdForKeyCode(108, mapOf("start" to 96)))
        GamepadManager.setArcadeModeActive(true)
        try {
            val actions = GamepadManager.mappableButtonActions().associateBy { it.id }
            assertEquals(139, actions.getValue(GamepadManager.resolveMappedActionIdForKeyCode(109, emptyMap())!!).padKey)
            assertEquals(140, actions.getValue(GamepadManager.resolveMappedActionIdForKeyCode(106, emptyMap())!!).padKey)
            assertEquals(141, actions.getValue(GamepadManager.resolveMappedActionIdForKeyCode(107, emptyMap())!!).padKey)
            assertEquals("start", GamepadManager.resolveMappedActionIdForKeyCode(108, emptyMap()))
            assertEquals("cross", GamepadManager.resolveMappedActionIdForKeyCode(106, mapOf("cross" to 106)))
            assertEquals("l3", GamepadManager.resolveMappedActionIdForKeyCode(106, mapOf("test" to 97)))
        } finally {
            GamepadManager.setArcadeModeActive(false)
        }
    }
    @Test
    fun inactiveArcadeBindingsDoNotReplaceConsoleButtons() {
        assertEquals("cross", GamepadManager.resolveMappedActionIdForKeyCode(96, mapOf("gun_trigger" to 96)))
        assertEquals("cross", GamepadManager.resolveMappedActionIdForKeyCode(96, mapOf("coin" to 96)))
        assertEquals("l2", GamepadManager.resolveMappedActionIdForTriggerAxis("l2", mapOf("gun_trigger" to 104)))
    }
    @Test
    fun explicitAssignmentMovesSelectedControllerToPlayerOne() {
        val assignments = GamepadManager.assignConnectedGamepadSlots(
            previousAssignments = emptyMap(),
            connectedDeviceIds = listOf(41, 72),
            singleGamepadReplacesTouch = true,
            deviceKeysByDeviceId = mapOf(41 to "xbox", 72 to "mangmi"),
            padDeviceKeys = mapOf(0 to "mangmi")
        )

        assertEquals(linkedMapOf(72 to 0, 41 to 1), assignments)
    }

    @Test
    fun explicitAssignmentOverridesExternalControllerPriority() {
        val assignments = GamepadManager.assignConnectedGamepadSlots(
            previousAssignments = emptyMap(),
            connectedDeviceIds = listOf(41, 72),
            singleGamepadReplacesTouch = true,
            preferExternalGamepadAsPlayerOne = true,
            externalDeviceIds = setOf(72),
            deviceKeysByDeviceId = mapOf(41 to "built-in", 72 to "external"),
            padDeviceKeys = mapOf(0 to "built-in")
        )

        assertEquals(linkedMapOf(41 to 0, 72 to 1), assignments)
    }

    @Test
    fun explicitPlayerTwoAssignmentKeepsOtherControllerOnPlayerOne() {
        val assignments = GamepadManager.assignConnectedGamepadSlots(
            previousAssignments = emptyMap(),
            connectedDeviceIds = listOf(41, 72),
            singleGamepadReplacesTouch = true,
            deviceKeysByDeviceId = mapOf(41 to "a", 72 to "b"),
            padDeviceKeys = mapOf(1 to "a")
        )

        assertEquals(linkedMapOf(72 to 0, 41 to 1), assignments)
    }

    @Test
    fun ignoredControllerIsExcludedFromSlots() {
        val assignments = GamepadManager.assignConnectedGamepadSlots(
            previousAssignments = emptyMap(),
            connectedDeviceIds = listOf(41, 72),
            singleGamepadReplacesTouch = true,
            deviceKeysByDeviceId = mapOf(41 to "xbox", 72 to "mangmi"),
            ignoredDeviceKeys = setOf("xbox")
        )

        assertEquals(linkedMapOf(72 to 0), assignments)
    }

    @Test
    fun explicitAssignmentOfDisconnectedControllerFallsBackToAutomatic() {
        val assignments = GamepadManager.assignConnectedGamepadSlots(
            previousAssignments = emptyMap(),
            connectedDeviceIds = listOf(41, 72),
            singleGamepadReplacesTouch = true,
            deviceKeysByDeviceId = mapOf(41 to "a", 72 to "b"),
            padDeviceKeys = mapOf(0 to "disconnected")
        )

        assertEquals(linkedMapOf(41 to 0, 72 to 1), assignments)
    }

    @Test
    fun ignoredExplicitControllerFallsBackToRemainingDevice() {
        val assignments = GamepadManager.assignConnectedGamepadSlots(
            previousAssignments = emptyMap(),
            connectedDeviceIds = listOf(41, 72),
            singleGamepadReplacesTouch = true,
            deviceKeysByDeviceId = mapOf(41 to "a", 72 to "b"),
            padDeviceKeys = mapOf(0 to "a"),
            ignoredDeviceKeys = setOf("a")
        )

        assertEquals(linkedMapOf(72 to 0), assignments)
    }

    @Test
    fun explicitPlayerOneAssignmentWinsInTouchPlusGamepadMode() {
        val assignments = GamepadManager.assignConnectedGamepadSlots(
            previousAssignments = emptyMap(),
            connectedDeviceIds = listOf(41),
            singleGamepadReplacesTouch = false,
            deviceKeysByDeviceId = mapOf(41 to "xbox"),
            padDeviceKeys = mapOf(0 to "xbox")
        )

        assertEquals(linkedMapOf(41 to 0), assignments)
    }
    @Test
    fun timeControlsCanBeRemappedWithoutLosingStartAndSelect() {
        val bindings = mapOf(GamepadManager.ACTION_FAST_FORWARD to 96)
        assertEquals(GamepadManager.ACTION_FAST_FORWARD, GamepadManager.resolveMappedActionIdForKeyCode(96, bindings))
        assertEquals("start", GamepadManager.resolveMappedActionIdForKeyCode(108, bindings))
        assertEquals("select", GamepadManager.resolveMappedActionIdForKeyCode(109, bindings))
        assertEquals("cross", GamepadManager.resolveMappedActionIdForKeyCode(96, emptyMap()))
    }
}
