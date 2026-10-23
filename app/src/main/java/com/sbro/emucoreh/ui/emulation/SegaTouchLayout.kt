package com.sbro.emucoreh.ui.emulation

import com.sbro.emucoreh.core.EmulationPlatform

internal data class SegaTouchButtonBounds(
    val keyCode: Int, val x: Float, val y: Float, val width: Float, val height: Float
)

/** All dimensions are in the available safe drawing area, after outer padding. */
internal fun segaTouchLayout(
    platform: EmulationPlatform, width: Float, height: Float, scale: Float
): List<SegaTouchButtonBounds> {
    if (width <= 0f || height <= 0f || !width.isFinite() || !height.isFinite()) return emptyList()
    val sixButtons = platform in setOf(EmulationPlatform.GENESIS, EmulationPlatform.SEGA_CD, EmulationPlatform.SEGA_32X)
    val systemButtons = if (sixButtons) listOf(109, 108) else
        if (platform == EmulationPlatform.SG_1000) emptyList() else listOf(108)
    // System buttons stay at the bottom. On narrow screens they get their own row.
    val diameter = minOf(48f * scale.coerceIn(0.5f, 1.5f), width / (if (sixButtons) 7f else 5.9f),
        height / (if (systemButtons.isEmpty()) 3.2f else 4.1f))
    val gap = diameter * 0.1f
    val faceColumns = if (sixButtons) 3 else 2
    val faceRows = if (sixButtons) 2 else 1
    val faceLeft = width - (faceColumns * diameter + (faceColumns - 1) * gap)
    val systemWidth = diameter * 1.75f
    val systemHeight = diameter * 0.8f
    val systemRowWidth = systemButtons.size * systemWidth + (systemButtons.size - 1).coerceAtLeast(0) * gap
    val centralGap = faceLeft - (3f * diameter + 2f * gap)
    val reserveSystemRow = systemButtons.isNotEmpty() && centralGap < systemRowWidth + 2f * gap
    val groupBottom = height - if (reserveSystemRow) systemHeight + gap else 0f
    val crossTop = (groupBottom - (3f * diameter + 2f * gap)).coerceAtLeast(0f)
    val faceTop = groupBottom - (faceRows * diameter + (faceRows - 1) * gap)
    return buildList {
        fun button(key: Int, x: Float, y: Float) = add(SegaTouchButtonBounds(key, x, y, diameter, diameter))
        button(19, diameter + gap, crossTop)
        button(21, 0f, crossTop + diameter + gap)
        button(22, 2f * (diameter + gap), crossTop + diameter + gap)
        button(20, diameter + gap, crossTop + 2f * (diameter + gap))
        val faces = if (sixButtons) listOf(102, 100, 103, 99, 96, 97) else listOf(96, 97)
        faces.forEachIndexed { index, key ->
            button(key, faceLeft + (index % faceColumns) * (diameter + gap),
                faceTop + (index / faceColumns) * (diameter + gap))
        }
        val systemLeft = (width - systemRowWidth) / 2f
        systemButtons.forEachIndexed { index, key ->
            add(SegaTouchButtonBounds(key, systemLeft + index * (systemWidth + gap),
                (height - systemHeight).coerceAtLeast(0f), systemWidth, systemHeight))
        }
    }
}
