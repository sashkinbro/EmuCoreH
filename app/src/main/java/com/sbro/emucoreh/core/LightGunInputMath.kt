package com.sbro.emucoreh.core

import kotlin.math.abs
import kotlin.math.sign

internal fun shapeGamepadStickAxis(value: Float, deadzone: Float, sensitivity: Float): Float {
    val safeDeadzone = deadzone.coerceIn(0f, 0.35f)
    val magnitude = abs(value)
    if (magnitude <= safeDeadzone) return 0f
    val normalized = ((magnitude - safeDeadzone) / (1f - safeDeadzone)).coerceIn(0f, 1f)
    return (normalized * sign(value) * sensitivity.coerceIn(0.5f, 2f)).coerceIn(-1f, 1f)
}

internal fun selectLightGunStick(
    activePorts: Set<Int>, padIndex: Int, aimSource: Int,
    left: Pair<Float, Float>, right: Pair<Float, Float>
): Pair<Float, Float>? {
    if (padIndex !in activePorts) return null
    return when (aimSource) {
        1 -> left
        2 -> right
        else -> null
    }
}

/**
 * Aim vector for the D-pad light gun source. Diagonals are normalized so
 * pressing two directions does not move the crosshair faster than a single
 * direction. Returns null while no direction is held.
 */
internal fun dpadAimVector(
    up: Boolean, down: Boolean, left: Boolean, right: Boolean
): Pair<Float, Float>? {
    val x = (if (right) 1f else 0f) - (if (left) 1f else 0f)
    val y = (if (down) 1f else 0f) - (if (up) 1f else 0f)
    if (x == 0f && y == 0f) return null
    if (x == 0f) return 0f to y
    if (y == 0f) return x to 0f
    val diagonal = 0.70710678f
    return x * diagonal to y * diagonal
}

internal class GyroLightGunAim {
    private var x = 0.5f
    private var y = 0.5f
    private var lastTimestampNs: Long? = null

    fun update(x: Float, y: Float, timestampNs: Long): Pair<Float, Float> {
        val dt = lastTimestampNs?.let { ((timestampNs - it) / 1_000_000_000f).coerceIn(0f, 0.05f) } ?: 0f
        lastTimestampNs = timestampNs
        this.x = (this.x + x.coerceIn(-1f, 1f) * dt * 1.05f).coerceIn(0f, 1f)
        this.y = (this.y + y.coerceIn(-1f, 1f) * dt * 1.05f).coerceIn(0f, 1f)
        return this.x to this.y
    }

    fun recenter() {
        x = 0.5f
        y = 0.5f
        lastTimestampNs = null
    }
}
