package com.sbro.emucoreh.core

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Owner-content performance probe; no retail data is bundled. */
@RunWith(AndroidJUnit4::class)
class SaturnPerformanceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun ownerChdRunsAtConsoleSpeed() {
        val uri = InstrumentationRegistry.getArguments().getString("saturnQaGameUri").orEmpty()
        assumeTrue("Supply a Saturn content URI", uri.startsWith("content://"))
        assumeTrue("Saturn BIOS must be installed", SaturnBios.isInstalled(context))
        val consumer = HandlerThread("Saturn-performance-pixels").apply { start() }
        val presented = AtomicInteger()
        val latest = java.util.concurrent.atomic.AtomicReference<ByteArray>()
        val reader = ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { image ->
                    presented.incrementAndGet()
                    val plane = image.planes[0]
                    val rgba = ByteArray(image.width * image.height * 4)
                    val buffer = plane.buffer.duplicate()
                    for (row in 0 until image.height) {
                        buffer.position(row * plane.rowStride)
                        buffer.get(rgba, row * image.width * 4, image.width * 4)
                    }
                    latest.set(rgba)
                }
            }, Handler(consumer.looper))
        }
        CoreRuntime.shutdown()
        try {
            CoreRuntime.initialize(context)
            val probe = runCatching {
                context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use {
                    CoreRuntime.bridge.probeSegaDiscDescriptor(it.fd)
                }
            }.getOrDefault(-1)
            Log.i("SaturnPerformanceQA", "probe=$probe detect=${SegaPlatformDetector.detect(context, uri)}")
            CoreRuntime.setHostForeground(true)
            CoreRuntime.setPerformanceMetricsEnabled(true, true)
            CoreRuntime.attachSurface(reader.surface, 640, 480)
            assertTrue("Start Saturn content: ${CoreRuntime.failure.value}", CoreRuntime.start(uri, false))
            assertEquals(EmulationPlatform.SATURN, CoreRuntime.platform)
            val deadline = SystemClock.uptimeMillis() + 20_000
            while (presented.get() < 120 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            // Sonic R: Start skips the intro, Start opens the mode menu, A picks
            // the character, then A confirms the course and starts the race.
            fun press(bit: Int) {
                CoreRuntime.setPadButtons(0, 0xFFFF and (1 shl bit).inv())
                SystemClock.sleep(140)
                CoreRuntime.setPadButtons(0, 0xFFFF)
                SystemClock.sleep(900)
            }
            fun saveFrame(name: String) {
                latest.get()?.let { rgba ->
                    val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba))
                        java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        Log.i("SaturnPerformanceQA", "saved $name")
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            saveFrame("saturn-00-menu.png")
            // EmuCoreH active-low bits: Start=3, A/Square=15.
            press(3)
            saveFrame("saturn-01-start.png")
            press(3)
            saveFrame("saturn-02-mode.png")
            press(15)
            saveFrame("saturn-03-character.png")
            press(15)
            val end = SystemClock.uptimeMillis() + 30_000
            var nextReport = SystemClock.uptimeMillis()
            var snapshot = 4
            while (SystemClock.uptimeMillis() < end) {
                SystemClock.sleep(100)
                val now = SystemClock.uptimeMillis()
                if (now >= nextReport) {
                    val sample = CoreRuntime.performanceMetricsSnapshot()
                    Log.i("SaturnPerformanceQA", "sample presented=${presented.get()} metrics=${sample?.replace('\n', '|')}")
                    if (snapshot <= 7) saveFrame("saturn-0$snapshot-race.png")
                    snapshot++
                    nextReport = now + 5_000
                }
            }
            val metrics = CoreRuntime.performanceMetricsSnapshot()
            Log.i("SaturnPerformanceQA", "presented=${presented.get()} metrics=${metrics?.replace('\n', '|')}")
            assertNotNull("Performance metrics", metrics)
            val speed = metrics!!.lineSequence().drop(1).firstOrNull()?.toDoubleOrNull()
            assertNotNull("Speed metric", speed)
            Log.i("SaturnPerformanceQA", "speed=$speed%")
            saveFrame("saturn-performance.png")
        } finally {
            CoreRuntime.shutdown()
            CoreRuntime.detachSurface()
            CoreRuntime.setHostForeground(false)
            CoreRuntime.setPerformanceMetricsEnabled(false, false)
            reader.setOnImageAvailableListener(null, null)
            reader.close()
            consumer.quitSafely()
            consumer.join(3000)
        }
    }
}
