package com.sbro.emucoreh.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGroupScalingTest {
    private fun members(): List<OverlayGroupScaleMember> = listOf(
        OverlayGroupScaleMember("up", baseX = 300f, baseY = 200f, offsetX = 0f, offsetY = 0f, scale = 100, width = 40f, height = 40f),
        OverlayGroupScaleMember("down", baseX = 300f, baseY = 280f, offsetX = 0f, offsetY = 0f, scale = 100, width = 40f, height = 40f),
        OverlayGroupScaleMember("left", baseX = 260f, baseY = 240f, offsetX = 0f, offsetY = 0f, scale = 100, width = 40f, height = 40f),
        OverlayGroupScaleMember("right", baseX = 340f, baseY = 240f, offsetX = 0f, offsetY = 0f, scale = 100, width = 40f, height = 40f)
    )

    @Test
    fun `group scale grows every member around the shared centre`() {
        val results = scaleOverlayControlGroup(
            members = members(),
            factor = 1.1f,
            minScale = 50,
            maxScale = 500,
            canvasWidth = 1000f,
            canvasHeight = 1000f
        )

        assertEquals(4, results.size)
        results.forEach { assertEquals(110, it.scale) }

        val up = results.first { it.controlId == "up" }
        val down = results.first { it.controlId == "down" }
        val left = results.first { it.controlId == "left" }
        val right = results.first { it.controlId == "right" }
        // The distance between the group members must stay proportional.
        val upTop = 0f + up.offset.second
        val downTop = 80f + down.offset.second
        val leftLeft = 60f + left.offset.first
        val rightLeft = 140f + right.offset.first
        assertEquals(88f, downTop - upTop, 0.01f)
        assertEquals(88f, rightLeft - leftLeft, 0.01f)
    }

    @Test
    fun `scaled offsets stay inside the canvas`() {
        val results = scaleOverlayControlGroup(
            members = listOf(
                OverlayGroupScaleMember(
                    controlId = "edge",
                    baseX = 0f,
                    baseY = 0f,
                    offsetX = 0f,
                    offsetY = 0f,
                    scale = 100,
                    width = 40f,
                    height = 40f
                )
            ),
            factor = 4f,
            minScale = 50,
            maxScale = 500,
            canvasWidth = 100f,
            canvasHeight = 100f
        )

        val edge = results.single()
        assertEquals(0f, edge.offset.first, 0.01f)
        assertEquals(0f, edge.offset.second, 0.01f)
        assertEquals(400, edge.scale)
    }

    @Test
    fun `scale is clamped to the allowed range`() {
        val results = scaleOverlayControlGroup(
            members = members(),
            factor = 10f,
            minScale = 50,
            maxScale = 200,
            canvasWidth = 4000f,
            canvasHeight = 4000f
        )

        results.forEach { assertEquals(200, it.scale) }
    }

    @Test
    fun `invalid input returns no updates`() {
        assertTrue(
            scaleOverlayControlGroup(emptyList(), 1.1f, 50, 500, 100f, 100f).isEmpty()
        )
        assertTrue(
            scaleOverlayControlGroup(members(), 0f, 50, 500, 100f, 100f).isEmpty()
        )
    }
}