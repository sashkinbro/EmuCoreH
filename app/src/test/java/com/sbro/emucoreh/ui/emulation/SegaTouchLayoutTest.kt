package com.sbro.emucoreh.ui.emulation

import com.sbro.emucoreh.core.EmulationPlatform
import org.junit.Assert.*
import org.junit.Test

class SegaTouchLayoutTest {
    @Test fun everyPlatformFitsPortraitLandscapeAndShortScreensAtEveryScale() {
        for (platform in EmulationPlatform.entries.filter { it.isSega }) {
            for ((width, height) in listOf(296f to 544f, 544f to 216f, 176f to 96f, 800f to 320f)) {
                for (scale in listOf(0.5f, 1f, 1.5f)) {
                    val buttons = segaTouchLayout(platform, width, height, scale)
                    assertTrue(buttons.isNotEmpty())
                    buttons.forEach { button ->
                        assertTrue(button.x >= 0f && button.y >= 0f)
                        assertTrue(button.x + button.width <= width + 0.001f)
                        assertTrue(button.y + button.height <= height + 0.001f)
                    }
                    buttons.forEachIndexed { index, a ->
                        buttons.drop(index + 1).forEach { b ->
                            assertFalse("$platform $width x $height overlaps ${a.keyCode}/${b.keyCode}",
                                a.x < b.x + b.width && b.x < a.x + a.width &&
                                    a.y < b.y + b.height && b.y < a.y + a.height)
                        }
                    }
                }
            }
        }
    }

    @Test fun dpadIsACrossAndHardwareButtonsRemainAccurate() {
        val md = segaTouchLayout(EmulationPlatform.GENESIS, 400f, 300f, 1f).associateBy { it.keyCode }
        assertEquals(md.getValue(19).x, md.getValue(20).x)
        assertEquals(md.getValue(21).y, md.getValue(22).y)
        assertTrue(md.getValue(19).y < md.getValue(21).y)
        assertTrue(md.getValue(20).y > md.getValue(21).y)
        assertEquals(setOf(19, 20, 21, 22, 96, 97, 99, 100, 102, 103, 108, 109), md.keys)
        val sg = segaTouchLayout(EmulationPlatform.SG_1000, 400f, 300f, 1f)
        assertEquals(setOf(19, 20, 21, 22, 96, 97), sg.map { it.keyCode }.toSet())
    }
    @Test fun startAndModeDefaultToTheBottomInBothOrientations() {
        for (platform in EmulationPlatform.entries.filter { it.isSega }) {
            for ((width, height) in listOf(296f to 544f, 711f to 327f)) {
                for (scale in listOf(0.5f, 1f, 1.5f)) {
                    segaTouchLayout(platform, width, height, scale).filter { it.keyCode in setOf(108, 109) }.forEach {
                        assertEquals(height, it.y + it.height, 0.001f)
                    }
                }
            }
        }
    }

}
