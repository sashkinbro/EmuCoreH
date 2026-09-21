package com.sbro.emucoreh.ui.common

import org.junit.Assert.*
import org.junit.Test

class CoverArtworkBoundsTest {
    @Test fun removesTransparentMarginsAndFaintShadow() {
        val pixels = IntArray(30) { 0x20000000 }
        pixels[1 * 5 + 1] = 0xff000000.toInt()
        pixels[4 * 5 + 3] = 0xffffffff.toInt()
        assertEquals(CoverArtworkBounds(1, 1, 3, 4), opaqueCoverBounds(pixels, 5, 6))
    }

    @Test fun preservesOpaqueImagesIncludingBlackEdges() {
        assertEquals(CoverArtworkBounds(0, 0, 3, 4), opaqueCoverBounds(IntArray(12) { 0xff000000.toInt() }, 3, 4))
    }

    @Test fun emptyCanvasHasNoCrop() {
        assertNull(opaqueCoverBounds(IntArray(12), 3, 4))
    }
}
