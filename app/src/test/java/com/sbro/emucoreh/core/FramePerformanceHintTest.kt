package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class FramePerformanceHintTest {
    private class Sink : FrameHintSink {
        val targets = mutableListOf<Long>()
        val durations = mutableListOf<Long>()
        var closed = 0
        override fun updateTarget(nanos: Long) { targets += nanos }
        override fun report(nanos: Long) { durations += nanos }
        override fun close() { closed++ }
    }

    @Test fun reportsActualWorkAndUpdatesOnlySignificantTargetChanges() {
        val sink = Sink()
        val hint = FramePerformanceHint(sink, 16_666_667L)
        hint.report(12_000_000L, 16_666_666L)
        hint.report(18_000_000L, 20_000_000L)
        hint.report(0L, 20_000_000L)
        assertEquals(listOf(20_000_000L), sink.targets)
        assertEquals(listOf(12_000_000L, 18_000_000L), sink.durations)
        hint.close()
        hint.close()
        assertEquals(1, sink.closed)
    }

    @Test fun failedSessionStopsReportingAndClosesOnce() {
        var closes = 0
        val sink = object : FrameHintSink {
            override fun updateTarget(nanos: Long) = Unit
            override fun report(nanos: Long) { error("session disconnected") }
            override fun close() { closes++ }
        }
        val hint = FramePerformanceHint(sink, 16_666_667L)
        hint.report(12_000_000L, 16_666_667L)
        hint.report(12_000_000L, 16_666_667L)
        hint.close()
        assertEquals(1, closes)
    }
}
