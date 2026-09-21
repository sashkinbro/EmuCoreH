package com.sbro.emucoreh.core

import kotlin.math.ceil

/** Keeps frame deadlines steady across scheduler jitter and drops long-stall debt. */
internal class FramePacer {
    private var lastStartNanos: Long? = null
    private var deadlineNanos: Long? = null
    private var periodNanos = 0L

    fun remainingNanos(nowNanos: Long, framesPerSecond: Double): Long {
        if (!framesPerSecond.isFinite() || framesPerSecond <= 0.0) return 0L
        val period = ceil(1_000_000_000.0 / framesPerSecond).toLong()
        if (period != periodNanos) {
            periodNanos = period
            deadlineNanos = lastStartNanos?.plus(period)
        }
        val earliest = lastStartNanos?.plus(periodNanos / 2) ?: nowNanos
        return (maxOf(deadlineNanos ?: nowNanos, earliest) - nowNanos).coerceAtLeast(0L)
    }

    fun frameStarted(nowNanos: Long) {
        lastStartNanos = nowNanos
        val deadline = deadlineNanos
        deadlineNanos = if (deadline == null || nowNanos - deadline >= periodNanos * 4) {
            nowNanos + periodNanos
        } else {
            deadline + periodNanos
        }
    }

    fun reset() {
        lastStartNanos = null
        deadlineNanos = null
        periodNanos = 0L
    }
}
