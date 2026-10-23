package com.sbro.emucoreh.core

import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** A self-authored SMS ROM programs a red backdrop, then loops without advancing CPU state. */
class SegaSoftwareSavePreviewInstrumentedTest {
    @Test fun pausedSoftwareSnapshotKeepsRealOpaquePixelsWithoutSurfaceAndClearsOnNextSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse("Run preview QA with no active game", CoreRuntime.hasSession())
        val root = File(context.cacheDir, "software-preview-${UUID.randomUUID()}").apply { mkdirs() }
        val consumer = HandlerThread("SegaPreviewFrames").apply { start() }
        val bridge = CoreRuntime.bridge
        val rom = File(root, "red.sms").apply {
            writeBytes(ByteArray(32768).apply {
                // Mode 4 is selected in VDP register 0, not register 1. Initialize
                // every palette entry so both transparent tiles and backdrop are red.
                val code = intArrayOf(0xf3, 0x3e, 0x04, 0xd3, 0xbf, 0x3e, 0x80, 0xd3, 0xbf,
                    0x3e, 0x40, 0xd3, 0xbf, 0x3e, 0x81, 0xd3, 0xbf,
                    0x3e, 0x00, 0xd3, 0xbf, 0x3e, 0xc0, 0xd3, 0xbf,
                    0x3e, 0x03, 0x06, 0x20, 0xd3, 0xbe, 0x10, 0xfc, 0x18, 0xfe)
                code.forEachIndexed { index, value -> this[index] = value.toByte() }
            })
        }
        var handle = 0L
        try {
            assertTrue(bridge.nativeSelectCore(1))
            bridge.nativeInit(root.path, root.path, root.path)
            handle = bridge.createSession()
            assertNotEquals(0L, handle)
            assertNull(bridge.captureSoftwareFrame(handle))
            ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 3).use { reader ->
                reader.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.close() }, Handler(consumer.looper))
                assertEquals(0, bridge.setSurface(handle, reader.surface, RendererDefaults.CORE_OPENGL))
                assertEquals(0, bridge.loadDisc(handle, rom.path))
                repeat(10) { bridge.runFrame(handle) }
                val beforePause = requireNotNull(bridge.captureSoftwareFrame(handle))
                val bitmap = requireNotNull(createSoftwareSaveStatePreviewBitmap(beforePause))
                try {
                    val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    assertTrue("Fixture must emit a visible red frame, not black", Color.red(pixel) > 128)
                    assertEquals(255, Color.alpha(pixel))
                    assertEquals(0, bridge.setSurface(handle, null, RendererDefaults.CORE_OPENGL))
                    // No retro_run and no Android window: a paused/covered game still owns its source image.
                    assertArrayEquals(beforePause, bridge.captureSoftwareFrame(handle))
                    val state = File(root, "paused.rawstate")
                    assertEquals(0, bridge.saveState(handle, state.path))
                    assertArrayEquals(beforePause, bridge.captureSoftwareFrame(handle))
                } finally { bitmap.recycle() }
                bridge.destroySession(handle)
                handle = 0L
            }
            assertNull(bridge.captureSoftwareFrame(0))
            handle = bridge.createSession()
            assertNull("A new session must not expose the previous game's image", bridge.captureSoftwareFrame(handle))
        } finally {
            if (handle != 0L) bridge.destroySession(handle)
            bridge.nativeSelectCore(0)
            CoreRuntime.initialize(context)
            consumer.quitSafely()
            consumer.join()
            root.deleteRecursively()
        }
    }
}
