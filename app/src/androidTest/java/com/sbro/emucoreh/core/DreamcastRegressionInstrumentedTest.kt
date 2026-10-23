package com.sbro.emucoreh.core

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Optional regression using owner-supplied content and disposable Flycast/VMU directories. */
class DreamcastRegressionInstrumentedTest {
    @Test fun ownerSuppliedDreamcastDiscPresentsAndRestoresWithIsolatedSaves() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val suppliedPath = arguments.getString("dreamcastQaPath")?.takeIf(String::isNotBlank)
        val game = if (suppliedPath != null) {
            File(suppliedPath).takeIf { it.isFile && it.canRead() }
        } else {
            val content = File(arguments.getString("dreamcastQaRoot")
                ?: "/storage/emulated/0/DreamcastGames/Games")
            assumeTrue("Owner-supplied Dreamcast folder is not accessible", content.isDirectory && content.canRead())
            val files = content.walkTopDown().maxDepth(3).filter { it.isFile && it.canRead() }.toList()
            files.filter { it.extension.equals("chd", true) }.sortedBy { it.name }.firstOrNull()
                ?: files.filter { it.extension.equals("cue", true) }.sortedBy { it.name }.firstOrNull()
        }
        assumeTrue("No readable owner-supplied Dreamcast disc", game != null)
        val disc = requireNotNull(game)
        // Every run gets a fresh directory. Never open or migrate the user's existing VMUs.
        val qa = File(context.cacheDir, "dreamcast-regression-qa/${System.currentTimeMillis()}")
            .apply { check(mkdirs()) }
        val system = File(qa, "system").apply { check(mkdirs()) }
        val save = File(qa, "save").apply { check(mkdirs()) }
        val assets = File(qa, "assets").apply { check(mkdirs()) }
        val report = mutableListOf("disc=${disc.absolutePath}", "qa=${qa.absolutePath}",
            "renderer=OpenGL ES", "bios=HLE", "audioOutput=none")
        val consumer = HandlerThread("DreamcastRegressionImages").apply { start() }
        val frames = AtomicInteger()
        val presented = CountDownLatch(5)
        val screenshot = AtomicReference<Bitmap>()
        val imageFailure = AtomicReference<Throwable>()
        var handle = 0L
        val bridge = CoreRuntime.bridge
        try {
            // Stops any earlier QA worker/audio output, leaving native audio pacing disabled.
            // This test does not call CoreRuntime.start(), which configures the real VMU paths.
            CoreRuntime.shutdown()
            assertTrue("Select Flycast", bridge.nativeSelectCore(0))
            bridge.nativeInit(system.absolutePath, save.absolutePath, assets.absolutePath)
            bridge.nativeSetOption("reicast_hle_bios", "enabled")
            bridge.nativeSetOption("reicast_internal_resolution", "640x480")
            bridge.nativeSetOption("reicast_per_content_vmus", "All VMUs")
            bridge.nativeSetOption("reicast_linked_vmu_storage", "disabled")
            bridge.nativeSetShaderPreset("", false)
            bridge.nativeSetShaderEffect(0)
            bridge.setFrameSkip(0)
            bridge.setDisplayCrop(0, 0, 0, 0)
            bridge.setDisplayAspectRatio(1)
            bridge.setTimeControl(0)
            bridge.resetAudioQueue()
            handle = bridge.createSession()
            assertTrue("Create isolated Flycast session", handle != 0L)
            assertEquals(1, bridge.apiVersion())
            assertTrue("Flycast selected: ${bridge.getSystemInfo()}", bridge.getSystemInfo().contains("Flycast", true))
            ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 3).use { reader ->
                reader.setOnImageAvailableListener({ source ->
                    try {
                        source.acquireLatestImage()?.use { image ->
                            val count = frames.incrementAndGet()
                            presented.countDown()
                            if (count % 30 == 0) {
                                val plane = image.planes[0]
                                check(plane.pixelStride == 4) { "Unexpected RGBA pixel stride ${plane.pixelStride}" }
                                val pixels = ByteArray(image.width * image.height * 4)
                                val buffer = plane.buffer.duplicate()
                                for (row in 0 until image.height) {
                                    buffer.position(row * plane.rowStride)
                                    buffer.get(pixels, row * image.width * 4, image.width * 4)
                                }
                                screenshot.set(Bitmap.createBitmap(image.width, image.height,
                                    Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(pixels)) })
                            }
                        }
                    } catch (error: Throwable) {
                        imageFailure.compareAndSet(null, error)
                    }
                }, Handler(consumer.looper))
                try {
                    // Every JNI operation touching EGL/GL stays on this instrumentation thread.
                    assertEquals(0, bridge.setSurface(handle, reader.surface, RendererDefaults.CORE_OPENGL))
                    assertTrue("Create GLES context", bridge.ensureHardwareContext())
                    assertEquals("Boot ${disc.name}; ${bridge.nativeLastBootError()}",
                        0, bridge.loadDisc(handle, disc.absolutePath))
                    assertTrue("Mounted Dreamcast disc", bridge.hasDiscMedia(handle))
                    assertTrue("Bind Flycast hardware renderer", bridge.ensureHardwareContext())
                    val baseline = requireNotNull(bridge.audioOutputStats(0))
                    val started = System.nanoTime()
                    repeat(180) { bridge.runFrame(handle) }
                    assertTrue("No GLES presentation after 180 frames; ${bridge.getDiagnostics()}",
                        presented.await(5, TimeUnit.SECONDS))
                    assertNull("ImageReader failed", imageFailure.get())
                    val state = File(qa, "dreamcast.rawstate")
                    assertEquals("Serialize Flycast state", 0, bridge.saveState(handle, state.absolutePath))
                    assertTrue("Nonempty raw Flycast state", state.isFile && state.length() > 0)
                    assertEquals("Restore Flycast state", 0, bridge.loadState(handle, state.absolutePath))
                    repeat(15) { bridge.runFrame(handle) }
                    assertTrue("Disc stays mounted after restore", bridge.hasDiscMedia(handle))
                    assertTrue(bridge.getSystemInfo().contains("Flycast", true))
                    val stats = requireNotNull(bridge.audioOutputStats(0))
                    assertTrue("Core emitted video frames", stats[9] > baseline[9])
                    assertTrue("Core emitted audio without an output stream", stats[5] > baseline[5])
                    screenshot.get()?.let { bitmap ->
                        File(qa, "dreamcast.png").outputStream().use {
                            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    }
                    report += "result=PASS"
                    report += "core=${bridge.getSystemInfo()}"
                    report += "presentedFrames=${frames.get()}"
                    report += "coreVideoFrames=${stats[9] - baseline[9]}"
                    report += "audioSourceFrames=${stats[5] - baseline[5]}"
                    report += "elapsedSeconds=${(System.nanoTime() - started) / 1e9}"
                    report += "stateBytes=${state.length()}"
                    Log.i("DreamcastRegressionQA", report.joinToString("; "))
                } finally {
                    if (handle != 0L) {
                        try { bridge.destroySession(handle) }
                        finally {
                            bridge.setSurface(handle, null, RendererDefaults.CORE_OPENGL)
                            handle = 0L
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            report += "result=FAIL ${error.javaClass.simpleName}: ${error.message}"
            throw error
        } finally {
            if (handle != 0L) bridge.destroySession(handle)
            consumer.quitSafely()
            consumer.join(5_000)
            bridge.resetAudioQueue()
            try {
                File(qa, "report.txt").writeText(report.joinToString("\n") + "\n")
                assertTrue("Clear test-only core overrides", bridge.nativeSelectCore(1))
                assertTrue("Flycast remains selectable after teardown", bridge.nativeSelectCore(0))
            } finally {
                CoreRuntime.initialize(context)
            }
        }
    }
}
