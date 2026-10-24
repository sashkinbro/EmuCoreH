package com.sbro.emucoreh.ui.common

import com.sbro.emucoreh.core.GamepadManager
import org.junit.Assert.assertEquals
import org.junit.Test

class GamepadActionOrderTest {
    @Test
    fun arcadeButtonsAreListedInTheirNumberedOrder() {
        val actions = listOf(
            action("cross"),
            action("circle"),
            action("square"),
            action("triangle"),
            action("l1"),
            action("r1"),
            action("l2"),
            action("r2"),
            action("select"),
            action("start")
        )

        assertEquals(
            listOf("cross", "circle", "square", "triangle", "r1", "l1", "r2", "l2", "select", "start"),
            actions.orderedForArcadeDisplay().map { it.id }
        )
    }

    private fun action(id: String) = GamepadManager.MappableButtonAction(id, null, emptyList())
}
