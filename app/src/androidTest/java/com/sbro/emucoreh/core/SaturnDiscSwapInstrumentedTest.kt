package com.sbro.emucoreh.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SaturnDiscSwapInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val bridge get() = CoreRuntime.bridge

    @Test fun rawIsoCueLoadsAndTraySwapSucceeds() {
        val bios = InstrumentationRegistry.getArguments().getString("saturnQaBios")?.let(::File)
        assumeTrue("Supply readable saturnQaBios", bios?.isFile == true && bios.canRead())
        requireNotNull(bios)
        val root = File(context.cacheDir, "saturn-disc-swap-qa-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val system = File(root, "system").apply { mkdirs() }
        val saves = File(root, "save").apply { mkdirs() }
        bios.copyTo(File(system, "saturn_bios.bin"), overwrite = true)
        fun disc(name: String): File {
            val file = File(root, name).apply {
                val header = "SEGA SEGASATURN ".toByteArray(Charsets.US_ASCII)
                val bytes = ByteArray(2048 * 40)
                header.copyInto(bytes, 0)
                header.copyInto(bytes, 16)
                writeBytes(bytes)
            }
            return file
        }
        val first = disc("first.iso")
        val second = disc("second.iso")
        val firstCue = SaturnContentPreparer.prepare(context, first.path)
        val secondCue = SaturnContentPreparer.prepare(context, second.path)
        val consumer = HandlerThread("Saturn-disc-swap-pixels").apply { start() }
        val reader = ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ source -> source.acquireLatestImage()?.use { } }, Handler(consumer.looper))
        }
        val output = NativeAudioOutput()
        CoreRuntime.shutdown()
        try {
            assertTrue(bridge.nativeSelectCore(3))
            bridge.nativeInit(system.path, saves.path, root.path)
            bridge.nativeSetOption("beetle_saturn_region", "North America")
            val handle = bridge.createSession()
            try {
                output.play()
                assertEquals(0, bridge.setSurface(handle, reader.surface, RendererDefaults.CORE_OPENGL))
                assertEquals("Load first disc: ${bridge.nativeLastBootError()}", 0, bridge.loadDisc(handle, firstCue))
                assertTrue("Disc tray has media", bridge.hasDiscMedia(handle))
                repeat(30) { bridge.runFrame(handle) }
                assertEquals("Swap to second disc", 0, bridge.changeDisc(handle, secondCue))
                assertTrue("Disc tray still has media", bridge.hasDiscMedia(handle))
                repeat(30) { bridge.runFrame(handle) }
            } finally {
                bridge.destroySession(handle)
                output.release()
            }
        } finally {
            SaturnContentPreparer.releasePreparedContent(context)
            bridge.nativeSelectCore(0)
            CoreRuntime.initialize(context)
            reader.setOnImageAvailableListener(null, null)
            reader.close()
            consumer.quitSafely()
            consumer.join(3000)
            root.deleteRecursively()
        }
    }
}
