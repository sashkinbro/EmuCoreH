package com.sbro.emucoreh.core

/** Measures guest progress, never the sound device's callbacks (which include silence). */
internal class EmulationTiming {
    data class Metrics(val fps: Double, val speedPercent: Double)
    private var previous: LongArray? = null
    private var previousNanos = 0L

    fun reset() {
        previous = null
    }

    fun sample(stats: LongArray?, nowNanos: Long): Metrics? {
        if (stats == null || stats.size < 10 || stats[8] <= 0L) {
            reset()
            return null
        }
        val before = previous
        val elapsed = nowNanos - previousNanos
        previous = stats
        previousNanos = nowNanos
        if (before == null || elapsed <= 0L || before[8] != stats[8] ||
            stats[5] < before[5] || stats[9] < before[9]) return null
        val seconds = elapsed / 1_000_000_000.0
        return Metrics((stats[9] - before[9]) / seconds,
            (stats[5] - before[5]) / stats[8].toDouble() / seconds * 100.0)
    }

    companion object {
        /** A retro_run can advance several vblanks in a 30/20 FPS scene. */
        fun pacingRate(refreshRate: Double, sourceFrames: Long, sampleRate: Long): Double =
            if (sourceFrames > 0L && sampleRate > 0L) sampleRate.toDouble() / sourceFrames
            else refreshRate

        fun speedMultiplier(refreshRate: Double, targetFps: Int): Double =
            if (targetFps in 20..120) targetFps / refreshRate else 1.0
    }
}
