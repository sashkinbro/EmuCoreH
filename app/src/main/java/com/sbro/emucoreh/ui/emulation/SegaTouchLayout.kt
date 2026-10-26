package com.sbro.emucoreh.ui.emulation

import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.ui.common.overlayDpadCrossGeometry

internal data class SegaTouchButtonBounds(
    val keyCode: Int, val x: Float, val y: Float, val width: Float, val height: Float
)

/** All dimensions are in the available safe drawing area, after outer padding. */
internal fun segaTouchLayout(
    platform: EmulationPlatform, width: Float, height: Float, scale: Float
): List<SegaTouchButtonBounds> {
    if (width <= 0f || height <= 0f || !width.isFinite() || !height.isFinite()) return emptyList()
    if (!platform.isModel3) return segaConsoleTouchLayout(platform, width, height, scale)
    val sixButtons = platform in setOf(EmulationPlatform.GENESIS, EmulationPlatform.SEGA_CD, EmulationPlatform.SEGA_32X, EmulationPlatform.MODEL_3)
    val systemButtons = if (platform.isModel3) listOf(109, 108, 106, 107) else if (sixButtons) listOf(109, 108) else
        if (platform == EmulationPlatform.SG_1000) emptyList() else listOf(108)
    // System buttons stay at the bottom. On narrow screens they get their own row.
    val diameter = minOf(48f * scale.coerceIn(0.5f, 1.5f), width / (if (platform.isModel3) 8.2f else if (sixButtons) 7f else 5.9f),
        height / (if (systemButtons.isEmpty()) 3.2f else 4.1f))
    val gap = diameter * 0.1f
    val faceColumns = if (platform.isModel3) 4 else if (sixButtons) 3 else 2
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
        val faces = if (platform.isModel3) listOf(102, 100, 103, 104, 99, 96, 97, 105) else
            if (sixButtons) listOf(102, 100, 103, 99, 96, 97) else listOf(96, 97)
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

/** Compact arrow cross and the ascending ABC/XYZ rows of a Sega console pad. */
private fun segaConsoleTouchLayout(
    platform: EmulationPlatform, width: Float, height: Float, scale: Float
): List<SegaTouchButtonBounds> {
    val six = platform in setOf(EmulationPlatform.GENESIS, EmulationPlatform.SEGA_CD, EmulationPlatform.SEGA_32X, EmulationPlatform.SATURN)
    val systems = if (platform.isSaturn) listOf(108) else if (six) listOf(109, 108) else if (platform == EmulationPlatform.SG_1000) emptyList() else listOf(108)
    val diameter = minOf(64f * scale.coerceIn(.5f, 1.5f), width / (if (six) 5.7f else 4.6f), height / 3.9f)
    val isLandscape = width >= height
    val dpadScale = diameter / 64f
    val dpad = overlayDpadCrossGeometry((if (isLandscape) 130f else 150f) * dpadScale, isLandscape)
    val crossSize = dpad.extent
    val faceWidth = diameter * (if (six) 3.2f else 2.1f)
    val faceLeft = width - faceWidth
    val systemHeight = diameter * .65f
    val systemWidth = diameter * 1.4f
    val systemGap = diameter * .1f
    val systemRowWidth = systems.size * systemWidth + (systems.size - 1).coerceAtLeast(0) * systemGap
    val systemLeft = (width - systemRowWidth) / 2f
    val reserveRow = systems.isNotEmpty() && (systemLeft < crossSize + diameter * .1f || systemLeft + systemRowWidth > faceLeft - diameter * .1f)
    val bottom = height - if (reserveRow) systemHeight + systemGap else 0f
    val faceBottom = bottom - diameter * .35f
    return buildList {
        fun button(key: Int, x: Float, y: Float, size: Float = diameter) =
            add(SegaTouchButtonBounds(key, x, y, size, size))
        val crossTop = bottom - crossSize
        button(19, dpad.centerOffset, crossTop, dpad.tile)
        button(21, 0f, crossTop + dpad.centerOffset, dpad.tile)
        button(22, dpad.step, crossTop + dpad.centerOffset, dpad.tile)
        button(20, dpad.centerOffset, crossTop + dpad.step, dpad.tile)
        val faces = if (six) listOf(102, 100, 103, 99, 96, 97) else listOf(96, 97)
        faces.forEachIndexed { index, key ->
            val column = index % (if (six) 3 else 2)
            val row = index / (if (six) 3 else 2)
            button(key, faceLeft + column * diameter * 1.1f,
                faceBottom - diameter - (if (six && row == 0) diameter * 1.1f else 0f) - column * diameter * .18f)
        }
        if (platform.isSaturn) {
            button(104, 0f, 0f, diameter * .7f)
            button(105, width - diameter * .7f, 0f, diameter * .7f)
        }
        systems.forEachIndexed { index, key ->
            add(SegaTouchButtonBounds(key, (width - systemRowWidth) / 2f + index * (systemWidth + systemGap),
                height - systemHeight, systemWidth, systemHeight))
        }
    }
}
