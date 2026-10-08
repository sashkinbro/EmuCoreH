package com.sbro.emucoreh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmulationTimingTest {
    private fun stats(audio: Long, video: Long, deviceHz: Long = 48000,
                      callbacks: Long = 0, silence: Long = 0) =
        longArrayOf(0, 0, deviceHz, 0, 0, audio, callbacks, silence, 44100, video)

    @Test fun thirtyAndTwentyFpsScenesStillRunAtFullSpeed() {
        for (fps in listOf(20L, 30L, 60L)) {
            val clock = EmulationTiming()
            assertNull(clock.sample(stats(100, 10), 0))
            val result = clock.sample(stats(44200, 10 + fps), 1_000_000_000)!!
            assertEquals(fps.toDouble(), result.fps, 0.001)
            assertEquals(100.0, result.speedPercent, 0.001)
        }
    }

    @Test fun deviceCallbacksAndUnderrunSilenceCannotHideSlowEmulation() {
        val clock = EmulationTiming()
        clock.sample(stats(0, 0), 0)
        val result = clock.sample(stats(22050, 15, callbacks = 48000, silence = 24000),
            1_000_000_000)!!
        assertEquals(50.0, result.speedPercent, 0.001)
        assertEquals(15.0, result.fps, 0.001)
        val stopped = clock.sample(stats(22050, 15, callbacks = 96000, silence = 72000),
            2_000_000_000)!!
        assertEquals(0.0, stopped.speedPercent, 0.001)
        assertEquals(0.0, stopped.fps, 0.001)
    }

    @Test fun fastForwardMeasuresGuestProgressEvenIfOutputDropsAudio() {
        val clock = EmulationTiming()
        clock.sample(stats(0, 0), 0)
        assertEquals(300.0, clock.sample(stats(132300, 180, callbacks = 48000),
            1_000_000_000)!!.speedPercent, 0.001)
    }

    @Test fun pauseAndCounterResetEstablishNewBaseline() {
        val clock = EmulationTiming()
        clock.sample(stats(44100, 30), 0)
        clock.reset()
        assertNull(clock.sample(stats(44100, 30), 10_000_000_000))
        assertEquals(100.0, clock.sample(stats(88200, 60),
            11_000_000_000)!!.speedPercent, 0.001)
        assertNull(clock.sample(stats(0, 0), 12_000_000_000))
    }

    @Test fun audioDeviceRecoveryDoesNotResetTheGuestClock() {
        val clock = EmulationTiming()
        clock.sample(stats(0, 0, 48000, 999999), 0)
        assertEquals(100.0, clock.sample(stats(44100, 60, 44100, 100),
            1_000_000_000)!!.speedPercent, 0.001)
    }

    @Test fun pacingFollowsGuestDurationWithoutSwapIntervalDetection() {
        assertEquals(60.0, EmulationTiming.pacingRate(60.0, 735, 44100), 0.001)
        assertEquals(30.0, EmulationTiming.pacingRate(60.0, 1470, 44100), 0.001)
        assertEquals(20.0, EmulationTiming.pacingRate(60.0, 2205, 44100), 0.001)
        assertEquals(50.0, EmulationTiming.pacingRate(50.0, 882, 44100), 0.001)
        assertEquals(50.0, EmulationTiming.pacingRate(50.0, 0, 44100), 0.001)
        // A 60 Hz manual target must not double a 30 FPS game's speed.
        assertEquals(1.0, EmulationTiming.speedMultiplier(60.0, 60), 0.001)
        assertEquals(1.2, EmulationTiming.speedMultiplier(50.0, 60), 0.001)
    }
}
