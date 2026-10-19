package com.sbro.emucoreh.ui.emulation

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchscreenRightStickGestureTest {

    @Test
    fun `gesture value scales by radius`() {
        val value = calculateRightStickGestureValue(
            deltaX = 48f,
            deltaY = 0f,
            radiusPx = 96f
        )

        assertEquals(0.5f, value.x, 0.0001f)
        assertEquals(0f, value.y, 0.0001f)
    }

    @Test
    fun `gesture value is clamped to the unit circle`() {
        val value = calculateRightStickGestureValue(
            deltaX = 300f,
            deltaY = 400f,
            radiusPx = 100f
        )

        assertEquals(0.6f, value.x, 0.0001f)
        assertEquals(0.8f, value.y, 0.0001f)
    }

    @Test
    fun `invalid radius returns zero`() {
        val value = calculateRightStickGestureValue(
            deltaX = 10f,
            deltaY = 10f,
            radiusPx = 0f
        )

        assertEquals(0f, value.x, 0.0001f)
        assertEquals(0f, value.y, 0.0001f)
    }

    @Test
    fun `disabled touchscreen sticks ignore empty space drags`() {
        assertEquals(
            TouchscreenStickGestureTarget.NONE,
            resolveTouchscreenStickGestureTarget(
                leftEnabled = false,
                rightEnabled = false,
                startX = 10f,
                widthPx = 100f
            )
        )
    }

    @Test
    fun `single enabled stick uses the whole free area`() {
        assertEquals(
            TouchscreenStickGestureTarget.RIGHT,
            resolveTouchscreenStickGestureTarget(
                leftEnabled = false,
                rightEnabled = true,
                startX = 10f,
                widthPx = 100f
            )
        )
        assertEquals(
            TouchscreenStickGestureTarget.LEFT,
            resolveTouchscreenStickGestureTarget(
                leftEnabled = true,
                rightEnabled = false,
                startX = 90f,
                widthPx = 100f
            )
        )
    }

    @Test
    fun `both enabled sticks split the free area in half`() {
        assertEquals(
            TouchscreenStickGestureTarget.LEFT,
            resolveTouchscreenStickGestureTarget(
                leftEnabled = true,
                rightEnabled = true,
                startX = 49f,
                widthPx = 100f
            )
        )
        assertEquals(
            TouchscreenStickGestureTarget.RIGHT,
            resolveTouchscreenStickGestureTarget(
                leftEnabled = true,
                rightEnabled = true,
                startX = 50f,
                widthPx = 100f
            )
        )
    }
}
