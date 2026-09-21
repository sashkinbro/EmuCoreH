package com.sbro.emucoreh.core

/** One press cycle; generation tokens invalidate delayed callbacks after release or reset. */
internal class HoldButton {
    var pressed = false
        private set
    var active = false
        private set
    var generation = 0
        private set

    fun press(): Int? {
        if (pressed) return null
        pressed = true
        return ++generation
    }

    fun activate(token: Int): Boolean {
        if (!pressed || generation != token) return false
        active = true
        return true
    }

    fun release(): Boolean {
        val tap = pressed && !active
        pressed = false
        active = false
        ++generation
        return tap
    }

    fun reset() { release() }
}
