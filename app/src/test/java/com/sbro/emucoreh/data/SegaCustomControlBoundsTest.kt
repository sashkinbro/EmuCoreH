package com.sbro.emucoreh.data

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class SegaCustomControlBoundsTest {
    @Test fun extremePositionsSizesRotationsAndPressedGrowthFitEveryViewport() {
        for ((width, height) in listOf(800f to 400f, 296f to 544f, 160f to 80f))
            for (position in listOf(-.5f, 0f, .5f, 1f, 1.5f))
                for (rotation in listOf(0f, 45f, 90f, -135f)) {
                    val bounds = segaCustomControlBounds(width, height, 180f, 180f, position, position, 1.4f, rotation)
                    assertTrue(bounds.x >= 0 && bounds.y >= 0)
                    assertTrue(bounds.x + bounds.width <= width + .001f)
                    assertTrue(bounds.y + bounds.height <= height + .001f)
                    val radians = Math.toRadians(rotation.toDouble())
                    val visualWidth = (bounds.width * abs(cos(radians)) + bounds.height * abs(sin(radians))) * 1.4
                    val visualHeight = (bounds.width * abs(sin(radians)) + bounds.height * abs(cos(radians))) * 1.4
                    val cx = bounds.x + bounds.width / 2
                    val cy = bounds.y + bounds.height / 2
                    assertTrue(cx - visualWidth / 2 >= -.001 && cx + visualWidth / 2 <= width + .001)
                    assertTrue(cy - visualHeight / 2 >= -.001 && cy + visualHeight / 2 <= height + .001)
                }
    }

    @Test fun invalidValuesHaveFiniteEmptyOrCenteredFallback() {
        assertEquals(SegaCustomControlBounds(0f, 0f, 0f, 0f),
            segaCustomControlBounds(Float.NaN, 100f, 40f, 40f, 0f, 0f))
        val bounds = segaCustomControlBounds(100f, 100f, 40f, 40f, Float.NaN, Float.POSITIVE_INFINITY)
        assertEquals(30f, bounds.x, .001f)
        assertEquals(30f, bounds.y, .001f)
    }
}
