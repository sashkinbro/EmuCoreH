package com.sbro.emucoreh.core

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Native code must stay mapped until callers' thread-local destructors have run. */
class NativeCoreLibraryLifetimeInstrumentedTest {
    @Test fun probeWorkerCanExitAfterCoreIsSwitchedAway() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "core-library-lifetime-qa").apply { mkdirs() }
        val cue = File(directory, "disc.cue").apply {
            writeText("FILE \"disc.bin\" BINARY\n TRACK 01 MODE1/2048\n INDEX 01 00:00:00\n")
        }
        File(directory, "disc.bin").writeBytes(ByteArray(2048 * 16).apply {
            "SEGADISCSYSTEM".toByteArray().copyInto(this)
        })
        CoreRuntime.shutdown()
        val bridge = CoreRuntime.bridge
        try {
            repeat(8) { iteration ->
                assertTrue(bridge.nativeSelectCore(1))
                assertEquals(1, bridge.apiVersion()) // Load the active core without creating a session.
                val probed = CountDownLatch(1)
                val finishWorker = CountDownLatch(1)
                val failure = AtomicReference<Throwable>()
                val worker = Thread({
                    try {
                        repeat(4) { assertEquals(1, bridge.probeSegaDisc(cue.absolutePath)) }
                    } catch (error: Throwable) {
                        failure.set(error)
                    } finally {
                        probed.countDown()
                        try { finishWorker.await(10, TimeUnit.SECONDS) }
                        catch (error: InterruptedException) { failure.compareAndSet(null, error) }
                    }
                }, "CoreTlsExit-$iteration").apply { start() }
                try {
                    assertTrue("Probe worker completed", probed.await(10, TimeUnit.SECONDS))
                    failure.get()?.let { throw AssertionError("Probe worker failed", it) }
                    assertTrue("Switch to Model 3 while the Rust caller remains alive", bridge.nativeSelectCore(2))
                    assertEquals(1, bridge.apiVersion())
                    assertEquals("Super3", bridge.getSystemInfo().substringBefore(' '))
                    assertTrue("Switch away while the Rust caller thread remains alive", bridge.nativeSelectCore(0))
                    assertEquals(1, bridge.apiVersion())
                } finally {
                    finishWorker.countDown()
                    worker.join(10_000)
                }
                assertFalse("Worker finished its native TLS cleanup", worker.isAlive)
                failure.get()?.let { throw AssertionError("Worker cleanup failed", it) }
            }
        } finally {
            assertTrue(bridge.nativeSelectCore(0))
            CoreRuntime.initialize(context)
        }
    }
}
