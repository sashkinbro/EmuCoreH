package com.sbro.emucoreh.core

import java.util.concurrent.atomic.AtomicIntegerArray

/** Physical, keyboard and mouse pressures stay independent until frame polling. */
internal class Model3TriggerPressure {
    companion object {
        // Touch/key buttons pass range=0; hardware axes pass their 1..255 pressure.
        fun digitalPress(range: Int, pressed: Boolean): Boolean = pressed && range <= 0

        fun suppressAxisKey(model3: Boolean, physicalKey: Int, mappedKey: Int,
            axisMappedKey: Int?, hasAxis: Boolean): Boolean = model3 &&
            physicalKey in 104..105 && mappedKey in 104..105 && mappedKey == axisMappedKey && hasAxis
    }
    private val sources = AtomicIntegerArray(3)
    fun update(source: Int, right: Boolean, range: Int, pressed: Boolean) {
        if (source !in 0..2) return
        val shift = if (right) 8 else 0
        val pressure = if (pressed) range.coerceIn(0, 255) else 0
        while (true) {
            val previous = sources.get(source)
            val next = (previous and (255 shl shift).inv()) or (pressure shl shift)
            if (sources.compareAndSet(source, previous, next)) return
        }
    }
    fun packed(): Int {
        var left = 0
        var right = 0
        for (source in 0..2) {
            val value = sources.get(source)
            left = maxOf(left, value and 255)
            right = maxOf(right, value ushr 8)
        }
        return left or (right shl 8)
    }
    fun reset() { for (source in 0..2) sources.set(source, 0) }
}
