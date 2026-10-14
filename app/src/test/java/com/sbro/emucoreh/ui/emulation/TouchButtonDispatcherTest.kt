package com.sbro.emucoreh.ui.emulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchButtonDispatcherTest {
    private class Fixture(targets: List<TouchButtonTarget> = faceButtons) {
        val events = mutableListOf<Pair<String, Boolean>>()
        val clicks = mutableListOf<String>()
        var pressed = emptySet<String>()
        val dispatcher = TouchButtonDispatcher(
            targets,
            { id, pressed -> events.add(id to pressed) },
            clicks::add,
            onStateChanged = { pressed = it }
        )
    }

    @Test
    fun intersectionPressesEveryFaceButtonAndReleasesEveryButtonOnUp() {
        val f = Fixture()
        assertTrue(f.dispatcher.pointerDown(1, 75f, 75f))
        assertEquals(listOf("x" to true, "y" to true, "b" to true, "a" to true), f.events)
        assertEquals(setOf("x", "y", "b", "a"), f.pressed)
        assertTrue(f.dispatcher.pointerUp(1, 75f, 75f))
        assertEquals(listOf("x" to false, "y" to false, "b" to false, "a" to false), f.events.takeLast(4))
        assertTrue(f.pressed.isEmpty())
    }

    @Test
    fun draggingIntoAndOutOfAnIntersectionOnlyChangesEnteredAndExitedButtons() {
        val f = Fixture()
        f.dispatcher.pointerDown(1, 25f, 25f)
        f.dispatcher.pointerMove(1, 75f, 25f)
        f.dispatcher.pointerMove(1, 125f, 25f)
        f.dispatcher.pointerMove(1, 200f, 25f)
        f.dispatcher.pointerMove(1, 25f, 25f)
        f.dispatcher.pointerUp(1, 25f, 25f)
        assertEquals(listOf("x" to true, "y" to true, "x" to false, "y" to false,
            "x" to true, "x" to false), f.events)
    }

    @Test
    fun liftingOneFingerKeepsButtonsHeldByAnotherFingerPressed() {
        val f = Fixture()
        f.dispatcher.pointerDown(1, 75f, 25f)
        f.dispatcher.pointerDown(2, 125f, 25f)
        f.dispatcher.pointerUp(1, 75f, 25f)
        assertEquals(listOf("x" to true, "y" to true, "x" to false), f.events)
        assertEquals(setOf("y"), f.pressed)
        f.dispatcher.pointerUp(2, 125f, 25f)
        assertEquals("y" to false, f.events.last())
    }

    @Test
    fun draggingOneFingerOutDoesNotReleaseTheOtherFingersButton() {
        val f = Fixture()
        f.dispatcher.pointerDown(1, 75f, 25f)
        f.dispatcher.pointerDown(2, 125f, 25f)
        f.dispatcher.pointerMove(1, 25f, 25f)
        assertEquals(listOf("x" to true, "y" to true), f.events)
        f.dispatcher.pointerUp(2, 125f, 25f)
        assertEquals(setOf("x"), f.pressed)
    }

    @Test
    fun cancellationReleasesEachHeldButtonOnceWithoutClicksOrToggles() {
        val f = Fixture()
        f.dispatcher.pointerDown(1, 75f, 75f)
        f.dispatcher.pointerDown(2, 75f, 75f)
        f.dispatcher.cancelPointers()
        f.dispatcher.cancelPointers()
        assertEquals(listOf("x" to true, "y" to true, "b" to true, "a" to true,
            "x" to false, "y" to false, "b" to false, "a" to false), f.events)
        assertTrue(f.clicks.isEmpty())
        assertFalse(f.dispatcher.pointerUp(1, 75f, 75f))
    }

    @Test
    fun legacyFaceNamesAlsoPressBothButtonsInTheIntersection() {
        val f = Fixture(listOf(
            TouchButtonTarget("square", 0f, 0f, 100f, 100f),
            TouchButtonTarget("triangle", 50f, 0f, 150f, 100f)
        ))
        f.dispatcher.pointerDown(1, 75f, 50f)
        assertEquals(listOf("square" to true, "triangle" to true), f.events)
    }

    @Test
    fun toggleButtonsLatchOnValidReleaseAndResetClearsTheLatch() {
        val f = Fixture(listOf(TouchButtonTarget("a", 0f, 0f, 100f, 100f, tapToHold = true)))
        f.dispatcher.pointerDown(1, 25f, 25f)
        assertTrue(f.events.isEmpty())
        f.dispatcher.pointerUp(1, 25f, 25f)
        assertEquals(listOf("a" to true), f.events)
        f.dispatcher.cancelPointers()
        assertEquals(setOf("a"), f.pressed)
        f.dispatcher.reset()
        assertEquals(listOf("a" to true, "a" to false), f.events)
    }

    @Test
    fun canceledToggleAndReleaseOutsideDoNotLatchOrClick() {
        val f = Fixture(listOf(TouchButtonTarget("a", 0f, 0f, 100f, 100f, tapToHold = true)))
        f.dispatcher.pointerDown(1, 25f, 25f)
        f.dispatcher.cancelPointers()
        f.dispatcher.pointerDown(2, 25f, 25f)
        f.dispatcher.pointerUp(2, 125f, 25f)
        assertTrue(f.events.isEmpty())
        assertTrue(f.clicks.isEmpty())
        assertTrue(f.pressed.isEmpty())
    }

    @Test
    fun pointerStartingOutsideButtonsRemainsAvailableForStickGestures() {
        val f = Fixture()
        assertFalse(f.dispatcher.pointerDown(1, 200f, 200f))
        assertFalse(f.dispatcher.pointerMove(1, 75f, 75f))
        assertFalse(f.dispatcher.pointerUp(1, 75f, 75f))
        assertTrue(f.events.isEmpty())
    }

    companion object {
        private val faceButtons = listOf(
            TouchButtonTarget("x", 0f, 0f, 100f, 100f),
            TouchButtonTarget("y", 50f, 0f, 150f, 100f),
            TouchButtonTarget("b", 50f, 50f, 150f, 150f),
            TouchButtonTarget("a", 0f, 50f, 100f, 150f)
        )
    }
}
