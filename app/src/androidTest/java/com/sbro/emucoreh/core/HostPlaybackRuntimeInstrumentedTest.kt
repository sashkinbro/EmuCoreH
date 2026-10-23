package com.sbro.emucoreh.core

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real core/audio regression using a self-authored Z80 RAM-counter ROM. */
class HostPlaybackRuntimeInstrumentedTest {
    @Test fun backgroundStartupResumeAndStateLoadRetainHostPauseIntent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "host-playback-qa").apply { mkdirs() }
        val rom = File(directory, "counter.sms").apply {
            // DI; LD HL,$C000; INC (HL); JR back to INC (HL).
            writeBytes(ByteArray(32768).apply {
                byteArrayOf(0xf3.toByte(), 0x21, 0, 0xc0.toByte(), 0x34, 0x18, 0xfd.toByte()).copyInto(this)
            })
        }
        val consumer = HandlerThread("HostPlaybackQaFrames").apply { start() }
        val frames = AtomicInteger()
        val previousSettings = CoreRuntime.settings.toMap()
        val previousOptions = CoreRuntime.launchCoreOptions
        val previousIdentity = CoreRuntime.launchIdentityPath
        CoreRuntime.shutdown()
        CoreRuntime.initialize(context)
        CoreRuntime.setDataRootOverride(directory.absolutePath)
        CoreRuntime.launchCoreOptions = emptyMap()
        CoreRuntime.launchIdentityPath = rom.absolutePath
        try {
            ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 3).use { reader ->
                reader.setOnImageAvailableListener({ source ->
                    source.acquireLatestImage()?.use { frames.incrementAndGet() }
                }, Handler(consumer.looper))
                instrumentation.runOnMainSync { CoreRuntime.attachSurface(reader.surface, 320, 240) }
                try {
                    // Equivalent to Home being pressed while the launch path is materialized.
                    CoreRuntime.setHostForeground(false)
                    assertTrue(CoreRuntime.start(rom.absolutePath, false))
                    assertQuiescent(directory, "background-start")

                    // A lifecycle foreground notification alone must not dismiss a menu/pause.
                    CoreRuntime.setHostForeground(true)
                    assertQuiescent(directory, "foreground-still-paused")
                    val beforeResume = frames.get()
                    val oldState = stateBytes(directory, "before-resume")
                    CoreRuntime.resume()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (frames.get() < beforeResume + 5 && System.nanoTime() < deadline) Thread.sleep(10)
                    assertTrue("Explicit resume presents frames", frames.get() >= beforeResume + 5)
                    assertEquals("Audio output is playing", 1L, audioStats()[0])
                    CoreRuntime.pause()
                    assertFalse("Emulated RAM/timing advanced", oldState.contentEquals(stateBytes(directory, "after-resume")))

                    CoreRuntime.setHostForeground(false)
                    assertTrue(CoreRuntime.loadState(File(directory, "before-resume.rstate").absolutePath))
                    CoreRuntime.resume() // A delayed foreground/resume operation must be ignored.
                    assertQuiescent(directory, "background-after-load")

                    CoreRuntime.shutdown()
                    assertTrue(CoreRuntime.bridge.nativeSelectCore(0))
                    assertTrue(CoreRuntime.bridge.getSystemInfo().contains("Flycast", ignoreCase = true))
                    // Switching cores/shutting down must not reset the host visibility flag.
                    assertTrue(CoreRuntime.start(rom.absolutePath, false))
                    assertQuiescent(directory, "background-next-session")
                } finally {
                    CoreRuntime.shutdown()
                    instrumentation.runOnMainSync { CoreRuntime.detachSurface() }
                }
            }
        } finally {
            CoreRuntime.shutdown()
            CoreRuntime.setHostForeground(true)
            CoreRuntime.setDataRootOverride(null)
            CoreRuntime.launchCoreOptions = previousOptions
            CoreRuntime.launchIdentityPath = previousIdentity
            CoreRuntime.bridge.nativeSelectCore(0)
            CoreRuntime.initialize(context)
            CoreRuntime.settings.clear()
            CoreRuntime.settings.putAll(previousSettings)
            consumer.quitSafely()
            consumer.join()
        }
    }

    private fun assertQuiescent(directory: File, name: String) {
        val before = stateBytes(directory, "$name-before")
        val audioBefore = audioStats()
        assertNotEquals("Audio output must not be started", 1L, audioBefore[0])
        Thread.sleep(250)
        assertArrayEquals("Paused emulation must not advance", before, stateBytes(directory, "$name-after"))
        val audioAfter = audioStats()
        assertEquals("No audio callbacks while paused", audioBefore[6], audioAfter[6])
        assertEquals("No emulated audio while paused", audioBefore[5], audioAfter[5])
    }

    private fun stateBytes(directory: File, name: String): ByteArray {
        val state = File(directory, "$name.rstate")
        assertTrue("Serialize $name", CoreRuntime.saveState(state.absolutePath))
        return state.readBytes()
    }

    // Keep diagnostics in the test: production does not expose an audio handle just for QA.
    private fun audioStats(): LongArray {
        val field = CoreRuntime::class.java.getDeclaredField("audioOutput").apply { isAccessible = true }
        return (field.get(CoreRuntime) as NativeAudioOutput).stats()!!
    }
}
