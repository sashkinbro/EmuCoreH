package com.sbro.emucoreh.core

object ArcadeInputPolicy {
    fun isLightGun(device: Int, mode: Int, platform: EmulationPlatform = EmulationPlatform.DREAMCAST): Boolean =
        !platform.isSega && (device == 2 || (device == 0 && mode == 2))
}

data class AimPoint(val x: Float, val y: Float, val offscreen: Boolean)

object LightGunAimGeometry {
    fun fromWindow(x: Float, y: Float, rect: FloatArray): AimPoint {
        if (rect.size < 4 || rect.any { !it.isFinite() } || !x.isFinite() || !y.isFinite() ||
            rect[2] <= rect[0] || rect[3] <= rect[1]) return AimPoint(.5f, .5f, true)
        val nx = (x - rect[0]) / (rect[2] - rect[0])
        val ny = (y - rect[1]) / (rect[3] - rect[1])
        return AimPoint(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f), nx !in 0f..1f || ny !in 0f..1f)
    }
}

internal data class LightGunFrame(val aim: AimPoint, val buttons: Int)

/** Keeps short taps visible to Flycast even when touch release precedes the next guest frame. */
internal class LightGunInputState {
    private var aim = AimPoint(.5f, .5f, false)
    private var held = 0
    private var edges = 0
    private var edgeMask = 0
    private var edgeFrames = 0

    @Synchronized fun setAim(x: Float, y: Float, offscreen: Boolean) {
        aim = if (x.isFinite() && y.isFinite()) AimPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), offscreen)
        else AimPoint(.5f, .5f, true)
    }

    @Synchronized fun setButton(bit: Int, pressed: Boolean) {
        if (bit !in 0..5) return
        val mask = 1 shl bit
        if (pressed) {
            if (held and mask == 0) edges = edges or mask
            held = held or mask
        } else held = held and mask.inv()
    }

    @Synchronized fun nextFrame(): LightGunFrame {
        if (edges != 0) {
            edgeMask = edgeMask or edges
            edgeFrames = 3
            edges = 0
        }
        val result = LightGunFrame(aim, held or edgeMask)
        if (edgeFrames > 0 && --edgeFrames == 0) edgeMask = 0
        return result
    }

    @Synchronized fun reset(recenter: Boolean = true) {
        held = 0; edges = 0; edgeMask = 0; edgeFrames = 0
        if (recenter) aim = AimPoint(.5f, .5f, false)
    }
}
