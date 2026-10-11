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
