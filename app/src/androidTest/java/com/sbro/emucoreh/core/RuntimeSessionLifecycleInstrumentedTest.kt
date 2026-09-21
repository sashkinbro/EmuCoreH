// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeSessionLifecycleInstrumentedTest {
    @Test
    fun realRuntimePausesRestoresSurfacesAndRestartsWithoutAdvancingPausedState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse(CoreRuntime.isRunning())
        val previousSettings = CoreRuntime.settings.toMap()
        val previousRenderer = previousSettings["EmuCoreH:Renderer"]
            ?: previousSettings["EmuCoreH/GS:Renderer"]
            ?: RendererDefaults.defaultForHardware().toString()
        val rom = File.createTempFile("runtime-rom-", ".bin", context.cacheDir)
        val state = File.createTempFile("runtime-state-", ".rstate", context.cacheDir)
        val check = File.createTempFile("runtime-check-", ".rstate", context.cacheDir)
        val consumer = HandlerThread("RuntimeTest-ImageConsumer").apply { start() }
        val handler = Handler(consumer.looper)
        fun onUi(block: () -> Unit) = instrumentation.runOnMainSync(block)
        try {
            // Owned MIPS loop continuously changes RAM, making an unwanted
            // extra guest frame observable in a complete serialized snapshot.
            val bytes = ByteBuffer.allocate(512 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            listOf(0x3c08a000, 0x24090000, 0x25290001, 0xad090000.toInt(),
                0x1000fffd, 0).forEach { bytes.putInt(it) }
            rom.writeBytes(bytes.array())
            // This instrumented process's map only, never DataStore. In
            // particular, no real memory-card paths may reach shutdown/save.
            CoreRuntime.settings.clear()
            CoreRuntime.initialize(context)
            assertTrue(CoreRuntime.updateSetting("Folders", "Bios", rom.parent!!))
            assertTrue(CoreRuntime.updateSetting("Filenames", "BIOS", rom.name))
            assertTrue(CoreRuntime.updateSetting("EmuCoreH/GPU", "PGXP", "false"))
            for (restart in 0..1) {
                assertTrue(CoreRuntime.start("", biosOnly = true))
                for ((index, renderer) in listOf(RendererDefaults.SOFTWARE,
                    RendererDefaults.VULKAN, RendererDefaults.OPENGL).withIndex()) {
                    CoreRuntime.pause()
                    assertTrue(CoreRuntime.updateSetting("EmuCoreH", "Renderer", renderer.toString()))
                    val width = 64 + index * 32
                    val frames = AtomicInteger()
                    ImageReader.newInstance(width, 96, PixelFormat.RGBA_8888, 3).use { reader ->
                        reader.setOnImageAvailableListener({ source ->
                            source.acquireLatestImage()?.use { frames.incrementAndGet() }
                        }, handler)
                        onUi { CoreRuntime.attachSurface(reader.surface, width, 96) }
                        try {
                            CoreRuntime.resume()
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                            while (frames.get() < 3 && System.nanoTime() < deadline) Thread.sleep(5)
                            assertTrue("renderer $renderer restart $restart did not present", frames.get() >= 3)
                            assertTrue(CoreRuntime.saveState(state.absolutePath))
                            val beforeReload = frames.get()
                            assertTrue(CoreRuntime.loadState(state.absolutePath))
                            val reloadDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                            while (frames.get() < beforeReload + 5 && System.nanoTime() < reloadDeadline)
                                Thread.sleep(5)
                            assertTrue("live state reload did not resume frame/audio output",
                                frames.get() >= beforeReload + 5)
                            CoreRuntime.pause()
                            assertTrue(CoreRuntime.isRunning())
                            assertTrue(CoreRuntime.saveState(state.absolutePath))
                            val paused = state.readBytes()
                            Thread.sleep(100)
                            assertTrue(CoreRuntime.saveState(check.absolutePath))
                            assertArrayEquals("guest advanced while paused", paused, check.readBytes())
                            check.writeBytes(byteArrayOf(0, 1, 2))
                            assertFalse(CoreRuntime.loadState(check.absolutePath))
                            assertTrue(CoreRuntime.saveState(check.absolutePath))
                            assertArrayEquals("rejected load changed the paused state", paused, check.readBytes())
                            onUi { CoreRuntime.detachSurface() }
                            assertTrue(CoreRuntime.loadState(state.absolutePath))
                        } finally {
                            onUi { CoreRuntime.detachSurface() }
                            reader.setOnImageAvailableListener(null, null)
                            // Drain callbacks queued before releasing their reader.
                            val drained = CountDownLatch(1)
                            handler.post { drained.countDown() }
                            assertTrue(drained.await(5, TimeUnit.SECONDS))
                        }
                    }
                }
                val frameWorker = Thread.getAllStackTraces().keys.single {
                    it.name == "EmuCoreH-Frame"
                }
                if (restart == 0) {
                    // Cancellation of a lifecycle caller must not abandon the
                    // native owner or consume that caller's interrupt status.
                    Thread.currentThread().interrupt()
                    try {
                        CoreRuntime.shutdown()
                        assertTrue("shutdown swallowed caller interruption",
                            Thread.currentThread().isInterrupted)
                    } finally {
                        Thread.interrupted() // Leave the instrumentation runner clean.
                    }
                } else {
                    CoreRuntime.shutdown()
                }
                assertFalse("shutdown returned with a live frame worker", frameWorker.isAlive)
                assertFalse(CoreRuntime.isRunning())
                assertFalse(CoreRuntime.saveState(check.absolutePath))
            }
            // Three simultaneous launch requests must replace sessions in
            // sequence, never publish several workers over one native handle.
            val callers = Executors.newFixedThreadPool(3)
            val launch = CountDownLatch(1)
            try {
                val results = (0..2).map {
                    callers.submit(Callable {
                        launch.await()
                        CoreRuntime.start("", biosOnly = true)
                    })
                }
                launch.countDown()
                results.forEach { assertTrue(it.get(15, TimeUnit.SECONDS)) }
                assertTrue(CoreRuntime.isRunning())
                assertTrue("concurrent starts left multiple frame workers",
                    Thread.getAllStackTraces().keys.count { it.name == "EmuCoreH-Frame" } == 1)
                CoreRuntime.shutdown()
                assertFalse(Thread.getAllStackTraces().keys.any { it.name == "EmuCoreH-Frame" })
            } finally {
                callers.shutdown()
                assertTrue(callers.awaitTermination(15, TimeUnit.SECONDS))
            }
        } finally {
            CoreRuntime.shutdown()
            CoreRuntime.detachSurface()
            CoreRuntime.updateSetting("EmuCoreH", "Renderer", previousRenderer)
            CoreRuntime.settings.clear()
            CoreRuntime.settings.putAll(previousSettings)
            CoreRuntime.initialize(instrumentation.targetContext)
            consumer.quitSafely()
            consumer.join(5_000)
            rom.delete()
            state.delete()
            check.delete()
        }
    }
}
