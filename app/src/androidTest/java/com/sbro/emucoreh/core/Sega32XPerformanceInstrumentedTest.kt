package com.sbro.emucoreh.core

import android.os.Bundle
import android.os.Debug
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in owner-ROM benchmark. Measures native frames with a drained offscreen surface, without app pacing. */
class Sega32XPerformanceInstrumentedTest {
    @Test fun reportsNative32xFrameCost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val romPath = arguments.getString("sega32xBenchmarkRom")
        assumeTrue("Supply sega32xBenchmarkRom with an app-readable owner ROM", !romPath.isNullOrBlank())
        val rom = File(requireNotNull(romPath))
        val warmupFrames = arguments.getString("sega32xBenchmarkWarmupFrames")?.toIntOrNull() ?: 600
        require(warmupFrames in 0..36_000)
        assertTrue("Benchmark ROM must be readable", rom.isFile && rom.canRead())
        val context = instrumentation.targetContext
        Sega32XBios.Part.entries.forEach { part ->
            assertTrue("Import ${part.fileName} before benchmarking", Sega32XBios.isInstalled(context, part))
        }
        val root = File(context.cacheDir, "sega-32x-performance-qa").apply { mkdirs() }
        val system = File(context.filesDir, "jgenesis/system")
        val save = File(root, "save").apply { mkdirs() }
        CoreRuntime.shutdown()
        val bridge = CoreRuntime.bridge
        assertTrue(bridge.nativeSelectCore(1))
        bridge.nativeInit(system.absolutePath, save.absolutePath, root.absolutePath)
        bridge.nativeSetOption("jgenesis_region", "auto")
        bridge.nativeSetOption("jgenesis_timing", "ntsc")
        bridge.resetAudioQueue()
        val handle = bridge.createSession()
        assertTrue(handle != 0L)
        val consumer = HandlerThread("Sega32xBenchmarkFrames").apply { start() }
        val frames = AtomicInteger()
        val reader = ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ source ->
            source.acquireLatestImage()?.use { frames.incrementAndGet() }
        }, Handler(consumer.looper))
        try {
            assertEquals(0, bridge.setSurface(handle, reader.surface, RendererDefaults.CORE_OPENGL))
            val loadResult = bridge.loadDisc(handle, rom.absolutePath)
            assertEquals("32X boot: ${bridge.nativeLastBootError()}", 0, loadResult)
            assertTrue("Offscreen graphics context must be ready", bridge.ensureHardwareContext())
            repeat(warmupFrames) { bridge.runFrame(handle) }
            val sourceFrame = requireNotNull(bridge.captureSoftwareFrame(handle))
            val preview = requireNotNull(createSoftwareSaveStatePreviewBitmap(sourceFrame))
            try {
                File(root, "frame-$warmupFrames.png").outputStream().use {
                    assertTrue(preview.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                preview.recycle()
            }
            repeat(3) { sample ->
                bridge.resetAudioQueue()
                val cpuStart = Debug.threadCpuTimeNanos()
                val start = System.nanoTime()
                repeat(60) { bridge.runFrame(handle) }
                val wallNanos = System.nanoTime() - start
                val cpuNanos = Debug.threadCpuTimeNanos() - cpuStart
                val report = String.format(Locale.US,
                    "sample=%d nativeWallMsPerFrame=%.3f threadCpuMsPerFrame=%.3f unpacedFramesPerSecond=%.2f",
                    sample, wallNanos / 60_000_000.0, cpuNanos / 60_000_000.0,
                    60_000_000_000.0 / wallNanos)
                Log.i("Sega32XPerformanceQA", report)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$report\n") })
            }
            val deadline = System.nanoTime() + 1_000_000_000L
            while (frames.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("Benchmark must execute and present real frames", frames.get() > 0)
        } finally {
            bridge.destroySession(handle)
            reader.close()
            consumer.quitSafely()
            consumer.join()
            assertTrue(bridge.nativeSelectCore(0))
            CoreRuntime.initialize(context)
        }
    }
}
