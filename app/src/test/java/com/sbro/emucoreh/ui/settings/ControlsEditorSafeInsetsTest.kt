package com.sbro.emucoreh.ui.settings

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlsEditorSafeInsetsTest {
    @Test
    fun cutoutInsetsAreKeptForEverySide() {
        val insets = resolveOverlaySafeInsets(
            cutoutLeft = 54.dp,
            cutoutRight = 18.dp,
            cutoutTop = 28.dp,
            cutoutBottom = 32.dp,
            navigationLeft = 0.dp,
            navigationRight = 0.dp,
            navigationTop = 0.dp,
            navigationBottom = 24.dp
        )

        assertEquals(54.dp, insets.left)
        assertEquals(18.dp, insets.right)
        assertEquals(28.dp, insets.top)
        assertEquals(32.dp, insets.bottom)
    }

    @Test
    fun navigationInsetWinsOverSmallerCutoutInset() {
        val insets = resolveOverlaySafeInsets(
            cutoutLeft = 10.dp,
            cutoutRight = 0.dp,
            cutoutTop = 0.dp,
            cutoutBottom = 0.dp,
            navigationLeft = 48.dp,
            navigationRight = 0.dp,
            navigationTop = 0.dp,
            navigationBottom = 0.dp
        )

        assertEquals(48.dp, insets.left)
        assertEquals(0.dp, insets.right)
    }

    @Test
    fun sidesWithoutInsetsStayAtZero() {
        val insets = resolveOverlaySafeInsets(
            cutoutLeft = 0.dp,
            cutoutRight = 0.dp,
            cutoutTop = 0.dp,
            cutoutBottom = 0.dp,
            navigationLeft = 0.dp,
            navigationRight = 0.dp,
            navigationTop = 0.dp,
            navigationBottom = 0.dp
        )

        assertEquals(0.dp, insets.left)
        assertEquals(0.dp, insets.right)
        assertEquals(0.dp, insets.top)
        assertEquals(0.dp, insets.bottom)
    }
}
