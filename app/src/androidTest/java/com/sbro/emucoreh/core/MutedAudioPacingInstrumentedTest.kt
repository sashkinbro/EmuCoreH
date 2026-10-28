package com.sbro.emucoreh.core

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MutedAudioPacingInstrumentedTest {
    @Test fun mutedOutputDoesNotThrottleAndTransitionsClearQueuedAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "muted-audio-pacing-qa").apply { mkdirs() }
        val rom = File(root, "counter.sms").apply {
            writeBytes(ByteArray(32768).apply {
                byteArrayOf(0xf3.toByte(), 0x21, 0, 0xc0.toByte(), 0x34, 0x18, 0xfd.toByte()).copyInto(this)
            })
        }
        val consumer = HandlerThread("MutedAudioQaFrames").apply { start() }
        val bridge = CoreRuntime.bridge
        CoreRuntime.shutdown()
        try {
            ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 3).use { reader ->
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
                assertTrue(bridge.nativeSelectCore(1))
                bridge.nativeInit(root.path, root.path, root.path)
                val handle = bridge.createSession()
                val output = NativeAudioOutput() // Not started: the queue cannot drain.
                try {
                    assertEquals(0, bridge.setSurface(handle, reader.surface, RendererDefaults.CORE_OPENGL))
                    assertEquals(0, bridge.loadDisc(handle, rom.path))
                    assertTrue(bridge.ensureHardwareContext())
                    bridge.resetAudioQueue()
                    output.setSystemMuted(true)
                    val started = System.nanoTime()
                    repeat(20) { bridge.runFrame(handle) }
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    fun buffered() = requireNotNull(output.stats())[4].toInt()
                    val queued = buffered()
                    assertTrue("Fixture must exceed the pacing watermark", queued > output.pacingHighWaterFrames())
                    assertTrue("Muted producer must not wait on the stalled audio output: $elapsedMs ms", elapsedMs < 2000)
                    output.setSystemMuted(true)
                    assertEquals("Repeated state must not discard audio", queued, buffered())
                    output.setSystemMuted(false)
                    assertEquals("Unmuting discards stale samples", 0, buffered())
                    bridge.runFrame(handle)
                    assertTrue(buffered() > 0)
                    output.setSystemMuted(true)
                    assertEquals("Muting clears the previous stream", 0, buffered())
                } finally {
                    output.setSystemMuted(false)
                    output.release()
                    bridge.destroySession(handle)
                }
            }
        } finally {
            bridge.nativeSelectCore(0)
            CoreRuntime.initialize(context)
            consumer.quitSafely()
            consumer.join(3000)
            root.deleteRecursively()
        }
    }
}
