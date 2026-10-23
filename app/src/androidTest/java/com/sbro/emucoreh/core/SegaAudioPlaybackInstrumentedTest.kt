package com.sbro.emucoreh.core

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in owner-ROM measurements through the actual paced runtime and AAudio output. */
class SegaAudioPlaybackInstrumentedTest {
    @Test fun reportsRealtimeAudioAndStopsPlaybackInBackground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("segaAudioRom")
        assumeTrue("Supply segaAudioRom with an app-readable owner ROM", !path.isNullOrBlank())
        val rom = File(requireNotNull(path))
        assertTrue("Owner ROM must be readable", rom.isFile && rom.canRead())
        val warmup = (args.getString("segaAudioWarmupSeconds")?.toIntOrNull() ?: 5).also { require(it in 0..120) }
        val duration = (args.getString("segaAudioMeasureSeconds")?.toIntOrNull() ?: 10).also { require(it in 2..60) }
        val previousSettings = CoreRuntime.settings.toMap()
        val previousOptions = CoreRuntime.launchCoreOptions
        val previousIdentity = CoreRuntime.launchIdentityPath
        val consumer = HandlerThread("SegaAudioQaFrames").apply { start() }
        CoreRuntime.shutdown()
        CoreRuntime.initialize(instrumentation.targetContext)
        CoreRuntime.launchCoreOptions = emptyMap()
        CoreRuntime.launchIdentityPath = rom.absolutePath
        CoreRuntime.setHostForeground(true)
        try {
            ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 3).use { reader ->
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
                instrumentation.runOnMainSync { CoreRuntime.attachSurface(reader.surface, 640, 480) }
                try {
                    assertTrue("Start owner game", CoreRuntime.start(rom.absolutePath, false))
                    CoreRuntime.resume()
                    Thread.sleep(warmup * 1000L)
                    val before = audioStats()
                    val started = System.nanoTime()
                    Thread.sleep(duration * 1000L)
                    val after = audioStats()
                    val seconds = (System.nanoTime() - started) / 1_000_000_000.0
                    val callbacks = after[6] - before[6]
                    val silence = after[7] - before[7]
                    val fps = (after[9] - before[9]) / seconds
                    val report = String.format(Locale.US,
                        "core=%s fps=%.2f seconds=%.2f sourceAudioFrames=%d callbackFrames=%d underrunFrames=%d underrunPercent=%.3f queue=%d",
                        CoreRuntime.platform.name, fps, seconds, after[5] - before[5], callbacks,
                        silence, if (callbacks > 0) silence * 100.0 / callbacks else 0.0, after[4])
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$report\n") })
                    File(instrumentation.targetContext.cacheDir, "sega-audio-playback-qa.txt").writeText(report)
                    assertEquals("Audio output is playing", 1L, after[0])
                    assertTrue("Runtime presented real frames", after[9] > before[9])
                    assertTrue("Core generated audio", after[5] > before[5])
                    assertTrue("AAudio consumed output", callbacks > 0)
                    CoreRuntime.setHostForeground(false)
                    Thread.sleep(100)
                    val paused = audioStats()
                    Thread.sleep(250)
                    val stillPaused = audioStats()
                    assertNotEquals("Background audio is stopped", 1L, paused[0])
                    assertEquals("Background audio callbacks stopped", paused[6], stillPaused[6])
                    assertEquals("Background game stopped", paused[9], stillPaused[9])
                } finally {
                    CoreRuntime.shutdown()
                    instrumentation.runOnMainSync { CoreRuntime.detachSurface() }
                }
            }
        } finally {
            CoreRuntime.shutdown()
            CoreRuntime.setHostForeground(true)
            CoreRuntime.launchCoreOptions = previousOptions
            CoreRuntime.launchIdentityPath = previousIdentity
            CoreRuntime.bridge.nativeSelectCore(0)
            CoreRuntime.initialize(instrumentation.targetContext)
            CoreRuntime.settings.clear()
            CoreRuntime.settings.putAll(previousSettings)
            consumer.quitSafely()
            consumer.join()
        }
    }

    private fun audioStats(): LongArray {
        val field = CoreRuntime::class.java.getDeclaredField("audioOutput").apply { isAccessible = true }
        return requireNotNull((field.get(CoreRuntime) as NativeAudioOutput).stats())
    }
}
