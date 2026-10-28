package com.sbro.emucoreh.core

import android.os.Build
import android.os.Process
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assume.assumeNotNull
import org.junit.Test

class FramePerformanceHintInstrumentedTest {
    @Test fun unsupportedVersionsFallbackAndSupportedSessionsClose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNull(createFramePerformanceHint(context, Process.myTid(), 0))
        val hint = createFramePerformanceHint(context, Process.myTid(), 16_666_667L)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            assertNull(hint)
            return
        }
        assumeNotNull(hint)
        requireNotNull(hint).apply {
            report(12_000_000L, 16_666_667L)
            report(18_000_000L, 20_000_000L)
            close()
            close()
        }
    }
    @Test fun runtimeClosesHintOnPauseAndReopensOnResume() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val supported = createFramePerformanceHint(context, Process.myTid(), 16_666_667L)
        assumeNotNull(supported)
        supported?.close()
        val root = File(context.cacheDir, "frame-hint-lifecycle-qa").apply { mkdirs() }
        val rom = File(root, "counter.sms").apply {
            writeBytes(ByteArray(32768).apply {
                byteArrayOf(0xf3.toByte(), 0x21, 0, 0xc0.toByte(), 0x34, 0x18, 0xfd.toByte()).copyInto(this)
            })
        }
        val consumer = HandlerThread("FrameHintQaFrames").apply { start() }
        val settings = CoreRuntime.settings.toMap()
        fun hasSession(): Boolean {
            val dump = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("dumpsys performance_hint")
            ).bufferedReader().use { it.readText() }
            return dump.contains("SessionPID: ${Process.myPid()}")
        }
        fun waitFor(expected: Boolean): Boolean {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline) {
                if (hasSession() == expected) return true
                SystemClock.sleep(100)
            }
            return false
        }
        CoreRuntime.shutdown()
        CoreRuntime.initialize(context)
        CoreRuntime.setHostForeground(true)
        CoreRuntime.updateSetting("EmuCoreH/Runtime", "Renderer", RendererDefaults.OPENGL.toString())
        try {
            ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 3).use { reader ->
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
                CoreRuntime.attachSurface(reader.surface, 320, 240)
                assertTrue(CoreRuntime.start(rom.path, false))
                CoreRuntime.resume()
                assertTrue("A running game should own a performance hint session", waitFor(true))
                CoreRuntime.pause()
                assertTrue("The session should close when the game is paused", waitFor(false))
                CoreRuntime.resume()
                assertTrue("Resuming should create a new session", waitFor(true))
                CoreRuntime.shutdown()
                assertTrue("Shutdown must close the session", waitFor(false))
                CoreRuntime.detachSurface()
            }
        } finally {
            CoreRuntime.shutdown()
            CoreRuntime.detachSurface()
            CoreRuntime.bridge.nativeSelectCore(0)
            CoreRuntime.settings.clear()
            CoreRuntime.settings.putAll(settings)
            consumer.quitSafely()
            consumer.join(3000)
            root.deleteRecursively()
        }
    }

}
