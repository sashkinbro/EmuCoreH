package com.sbro.emucoreh.core

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import androidx.annotation.RequiresApi
import kotlin.math.abs

internal interface FrameHintSink {
    fun updateTarget(nanos: Long)
    fun report(nanos: Long)
    fun close()
}

/** A single frame thread owns this session and closes it when it pauses or exits. */
internal class FramePerformanceHint(private val sink: FrameHintSink, initialTargetNanos: Long) {
    private var targetNanos = initialTargetNanos
    private var closed = false

    fun report(actualNanos: Long, newTargetNanos: Long) {
        if (closed || actualNanos <= 0L || newTargetNanos <= 0L) return
        try {
            // Fractional audio batches vary by a sample; don't send an IPC for each one.
            if (abs(newTargetNanos - targetNanos) > targetNanos / 20) {
                sink.updateTarget(newTargetNanos)
                targetNanos = newTargetNanos
            }
            sink.report(actualNanos)
        } catch (_: Exception) {
            close()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { sink.close() }
    }
}

internal fun createFramePerformanceHint(context: Context, threadId: Int, targetNanos: Long): FramePerformanceHint? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || targetNanos <= 0L) return null
    return runCatching { createFramePerformanceHintApi31(context, threadId, targetNanos) }.getOrNull()
}

@RequiresApi(Build.VERSION_CODES.S)
private fun createFramePerformanceHintApi31(context: Context, threadId: Int, targetNanos: Long): FramePerformanceHint? {
    val manager = context.getSystemService(PerformanceHintManager::class.java) ?: return null
    val session = manager.createHintSession(intArrayOf(threadId), targetNanos) ?: return null
    return FramePerformanceHint(object : FrameHintSink {
        override fun updateTarget(nanos: Long) = session.updateTargetWorkDuration(nanos)
        override fun report(nanos: Long) = session.reportActualWorkDuration(nanos)
        override fun close() = session.close()
    }, targetNanos)
}
