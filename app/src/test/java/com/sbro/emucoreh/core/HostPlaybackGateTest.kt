package com.sbro.emucoreh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HostPlaybackGateTest {
    @Test fun backgroundDuringPreparationPreventsAudioStarting() {
        val gate = HostPlaybackGate()
        val output = mutableListOf<String>()
        gate.setForeground(false) { output += "pause" }
        assertFalse(gate.runIfForeground { output += "play" })
        assertEquals(listOf("pause"), output)
    }

    @Test fun foregroundDoesNotResumeAnIntentionallyPausedSession() {
        val gate = HostPlaybackGate()
        val output = mutableListOf<String>()
        gate.setForeground(false) { output += "pause" }
        gate.setForeground(true) { output += "pause" }
        assertEquals(listOf("pause"), output)
        assertTrue(gate.runIfForeground { output += "play" })
        assertEquals(listOf("pause", "play"), output)
    }

    @Test fun backgroundIntentSurvivesAnAbortedLaunchAndNextSession() {
        val gate = HostPlaybackGate()
        gate.setForeground(false) {}
        repeat(2) { assertFalse(gate.runIfForeground { error("Background playback") }) }
        gate.setForeground(true) {}
        assertTrue(gate.runIfForeground {})
    }

    @Test fun headlessSessionsKeepTheirExistingPlaybackDefault() {
        var played = false
        assertTrue(HostPlaybackGate().runIfForeground { played = true })
        assertTrue(played)
    }

    @Test fun backgroundCannotSlipBetweenTheForegroundCheckAndAudioStart() {
        val gate = HostPlaybackGate()
        val playing = java.util.concurrent.atomic.AtomicBoolean(false)
        val enteredPlay = CountDownLatch(1)
        val finishPlay = CountDownLatch(1)
        val backgroundRequested = CountDownLatch(1)
        val starter = thread {
            gate.runIfForeground {
                enteredPlay.countDown()
                check(finishPlay.await(5, TimeUnit.SECONDS))
                playing.set(true)
            }
        }
        assertTrue(enteredPlay.await(5, TimeUnit.SECONDS))
        val background = thread {
            backgroundRequested.countDown()
            gate.setForeground(false) { playing.set(false) }
        }
        assertTrue(backgroundRequested.await(5, TimeUnit.SECONDS))
        finishPlay.countDown()
        starter.join(5000)
        background.join(5000)
        assertFalse(starter.isAlive)
        assertFalse(background.isAlive)
        assertFalse(playing.get())
        assertFalse(gate.runIfForeground { playing.set(true) })
    }
}
