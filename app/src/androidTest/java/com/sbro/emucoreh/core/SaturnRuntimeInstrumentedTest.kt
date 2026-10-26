package com.sbro.emucoreh.core

import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.ByteBuffer

/** Only authored failure fixtures are bundled. Retail content and firmware are owner supplied. */
@RunWith(AndroidJUnit4::class)
class SaturnRuntimeInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val bridge get() = CoreRuntime.bridge

    // Frame indices are zero-based; ranges include both ends. Example:
    // start@60-63;a@600-603;a@900-903;b@1800-end;left@2100-2130
    // Buttons may be combined, e.g. a+b@1800-end. An omitted argument preserves
    // the original short Start pulse every 120 frames.
    private data class InputWindow(val first: Int, val last: Int, val pressed: Int)

    private fun parseInputScript(script: String, frames: Int): List<InputWindow> {
        val bits = mapOf("start" to 3, "up" to 4, "right" to 5, "down" to 6, "left" to 7,
            "l" to 8, "r" to 9, "x" to 10, "z" to 11, "y" to 12, "c" to 13, "b" to 14, "a" to 15)
        return script.split(';').filter(String::isNotBlank).map { command ->
            val parts = command.trim().lowercase(java.util.Locale.ROOT).split('@')
            require(parts.size == 2) { "Expected buttons@first-last: $command" }
            val range = parts[1].split('-')
            require(range.size in 1..2) { "Invalid frame range: $command" }
            val first = range[0].toInt()
            val last = when (range.getOrNull(1)) { null -> first; "end" -> frames - 1; else -> range[1].toInt() }
            require(first in 0 until frames && last in first until frames) { "Input outside frame budget: $command" }
            val pressed = parts[0].split('+').fold(0) { mask, button ->
                mask or (1 shl requireNotNull(bits[button]) { "Unknown Saturn button: $button" })
            }
            InputWindow(first, last, pressed)
        }
    }

    private fun inputMask(script: List<InputWindow>, frame: Int): Int = 0xffff xor
        script.filter { frame in it.first..it.last }.fold(0) { mask, input -> mask or input.pressed }

    @Test fun scriptedInputUsesConfirmedSaturnButtonsAndInclusiveHeldRanges() {
        val inputs = parseInputScript("start@2-3;a@4-7;b+left@6-end", 12)
        assertEquals(0xffff, inputMask(inputs, 0))
        assertEquals(0xffff xor (1 shl 3), inputMask(inputs, 2))
        assertEquals(0xffff xor (1 shl 15), inputMask(inputs, 4))
        assertEquals(0xffff xor (1 shl 15) xor (1 shl 14) xor (1 shl 7), inputMask(inputs, 6))
        assertEquals(0xffff xor (1 shl 14) xor (1 shl 7), inputMask(inputs, 11))
        for (bad in listOf("a@-1-2", "unknown@1-2", "a@3-2", "a@0-12"))
            assertTrue(bad, runCatching { parseInputScript(bad, 12) }.isFailure)
    }

    @Test fun ownerSuppliedDiscMetadataIsIndependentOfActiveCoreAndDescriptor() {
        val path = InstrumentationRegistry.getArguments().getString("saturnQaGame") ?: ""
        val game = File(path)
        assumeTrue("Supply readable owner saturnQaGame", game.isFile && game.canRead())
        val metadata = requireNotNull(bridge.saturnDiscMetadata(path))
        val fields = metadata.split('\n')
        assertEquals(2, fields.size)
        assertTrue("Disc product code", fields[1].isNotBlank())
        ParcelFileDescriptor.open(game, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            assertEquals(metadata, bridge.saturnDiscMetadata("/proc/self/fd/${descriptor.fd}"))
            assertTrue("Borrowed descriptor remains valid", descriptor.fileDescriptor.valid())
        }
        Log.i("SaturnRuntimeQA", "Owner boot header: ${metadata.replace('\n', '|')}")
    }

    @Test fun missingFirmwareAndRepeatedCoreSwitchesRecover() {
        CoreRuntime.shutdown()
        val root = File(context.cacheDir, "saturn-missing-firmware-qa").apply { mkdirs() }
        val disc = File(root, "authored.iso").apply {
            writeBytes(ByteArray(2048 * 32).also { "SEGA SEGASATURN  ".toByteArray().copyInto(it) })
        }
        try {
            repeat(3) {
                assertTrue(bridge.nativeSelectCore(3))
                assertEquals(1, bridge.apiVersion())
                assertTrue(bridge.getSystemInfo().contains("YabaSanshiro"))
                val system = File(root, "empty-system").apply { mkdirs() }
                val saves = File(root, "save").apply { mkdirs() }
                bridge.nativeInit(system.path, saves.path, root.path)
                val handle = bridge.createSession()
                assertNotEquals(0L, handle)
                try {
                    assertNotEquals(0, bridge.loadDisc(handle, disc.path))
                    assertTrue("Actionable firmware error: ${bridge.nativeLastBootError()}",
                        bridge.nativeLastBootError().orEmpty().contains("BIOS", ignoreCase = true))
                    assertNotEquals(0, bridge.saveState(handle, File(root, "invalid.state").path))
                } finally { bridge.destroySession(handle) }
                for (core in listOf(1, 0, 2)) {
                    assertTrue("Switch after failed Saturn boot to core $core", bridge.nativeSelectCore(core))
                    assertEquals(1, bridge.apiVersion())
                }
            }
        } finally {
            bridge.nativeSelectCore(0)
            CoreRuntime.initialize(context)
        }
    }

    @Test fun ownerSuppliedDiscRepeatsRenderingStateAndSurfaceLifecycle() {
        val args = InstrumentationRegistry.getArguments()
        val game = args.getString("saturnQaGame")?.let(::File)
        val bios = args.getString("saturnQaBios")?.let(::File)
        assumeTrue("Supply saturnQaGame and saturnQaBios with readable owner files",
            game?.canRead() == true && bios?.canRead() == true)
        requireNotNull(game)
        requireNotNull(bios)
        val frames = args.getString("saturnQaFrames")?.toIntOrNull()?.coerceIn(120, 3600) ?: 360
        val seedState = args.getString("saturnQaRestoreState")?.let(::File)
        val diagnostics = args.getString("saturnQaDiagnostics") == "true"
        val launches = args.getString("saturnQaLaunches")?.toIntOrNull()?.coerceIn(1, 3) ?: 2
        val scriptArgument = args.getString("saturnQaInputScript")
        val script = scriptArgument?.let { parseInputScript(it, frames) }
        val root = File(context.getExternalFilesDir(null), "saturn-owner-runtime-qa").apply { mkdirs() }
        val system = File(root, "system").apply { mkdirs() }
        val saves = File(root, "save").apply { mkdirs() }
        bios.copyTo(File(system, "saturn_bios.bin"), overwrite = true)
        val consumer = HandlerThread("Saturn-QA-pixels").apply { start() }
        val presented = AtomicInteger()
        val visible = AtomicBoolean()
        val latest = AtomicReference<ByteArray>()
        val imageLock = Any()
        val closedReaders = mutableSetOf<ImageReader>()
        fun closeReader(reader: ImageReader) = synchronized(imageLock) {
            closedReaders += reader
            reader.setOnImageAvailableListener(null, null)
            reader.close()
        }
        fun screenshot(name: String) {
            val rgba = latest.get() ?: return
            val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            try {
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
                File(root, name).outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
        fun reader() = ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ source -> synchronized(imageLock) {
                if (source !in closedReaders) source.acquireLatestImage()?.use { image ->
                val plane = image.planes[0]
                val rgba = ByteArray(image.width * image.height * 4)
                val buffer = plane.buffer.duplicate()
                for (row in 0 until image.height) {
                    buffer.position(row * plane.rowStride)
                    buffer.get(rgba, row * image.width * 4, image.width * 4)
                }
                if ((0 until rgba.size step 64).any { offset ->
                    (rgba[offset].toInt() and 255) > 16 || (rgba[offset + 1].toInt() and 255) > 16 || (rgba[offset + 2].toInt() and 255) > 16
                }) visible.set(true)
                latest.set(rgba)
                presented.incrementAndGet()
                }
            } }, Handler(consumer.looper))
        }
        CoreRuntime.shutdown()
        try {
            repeat(launches) { launch ->
                presented.set(0)
                visible.set(false)
                latest.set(null)
                assertTrue(bridge.nativeSelectCore(3))
                bridge.nativeInit(system.path, saves.path, root.path)
                bridge.nativeSetOption("yabasanshiro_frameskip", "disabled")
                bridge.nativeSetOption("yabasanshiro_sh2coretype", "dynarec")
                bridge.resetAudioQueue()
                val output = NativeAudioOutput()
                var target = reader()
                val handle = bridge.createSession()
                try {
                    output.play()
                    assertEquals(0, bridge.setSurface(handle, target.surface, RendererDefaults.CORE_OPENGL))
                    assertEquals("Load: ${bridge.nativeLastBootError()}", 0, bridge.loadDisc(handle, game.path))
                    assertTrue("GLES 3.1 context", bridge.ensureHardwareContext())
                    if (seedState != null) {
                        assertTrue("Readable owner QA seed state", seedState.isFile && seedState.canRead())
                        bridge.runFrame(handle)
                        assertEquals("Restore owner QA seed", 0, bridge.loadState(handle, seedState.path))
                    }
                    bridge.resetSaturnAudioFingerprint()
                    assertFalse("Cannot switch running core", bridge.nativeSelectCore(1))
                    if (script != null) Log.i("SaturnRuntimeQA", "Input script ${game.name} launch$launch: $scriptArgument")
                    repeat(frames) { frame ->
                        val buttons = script?.let { inputMask(it, frame) }
                            ?: if (frame % 120 in 60..61) 0xffff xor (1 shl 3) else 0xffff
                        bridge.setPadButtons(handle, 0, buttons)
                        bridge.runFrame(handle)
                        if (script != null && (frame + 1) % 600 == 0) {
                            screenshot("${game.nameWithoutExtension}-$launch-frame${(frame + 1).toString().padStart(4, '0')}.png")
                        }
                        if (frame == frames / 2) {
                            output.pause()
                            assertEquals(0, bridge.setSurface(handle, null, RendererDefaults.CORE_OPENGL))
                            assertFalse(bridge.ensureHardwareContext())
                            val replacement = reader()
                            assertEquals(0, bridge.setSurface(handle, replacement.surface, RendererDefaults.CORE_OPENGL))
                            assertTrue(bridge.ensureHardwareContext())
                            closeReader(target)
                            target = replacement
                            output.play()
                        }
                    }
                    assertTrue("Video frames on launch $launch", presented.get() > 0)
                    assertTrue("Visible rendered content on launch $launch", visible.get())
                    assertTrue("Nonzero PCM samples on launch $launch", bridge.saturnAudioNonzeroSamples() > 0)
                    screenshot("${game.nameWithoutExtension}-$launch.png")
                    bridge.setPadButtons(handle, 0, 0xffff)
                    val state = File(root, "roundtrip-$launch.state")
                    assertEquals(0, bridge.saveState(handle, state.path))
                    assertTrue(state.length() > 1024)
                    bridge.resetSaturnAudioFingerprint()
                    repeat(12) {
                        bridge.runFrame(handle)
                        if (diagnostics) Log.i("SaturnRuntimeQA", "before frame=$it hash=${bridge.saturnAudioFingerprint()}")
                    }
                    val replayAudio = bridge.saturnAudioFingerprint()
                    if (diagnostics) assertEquals(0, bridge.saveState(handle, File(root, "replay-before-$launch.state").path))
                    assertEquals(0, bridge.loadState(handle, state.path))
                    if (diagnostics) assertEquals(0, bridge.saveState(handle, File(root, "restored-immediate-$launch.state").path))
                    bridge.resetSaturnAudioFingerprint()
                    repeat(12) {
                        bridge.runFrame(handle)
                        if (diagnostics) Log.i("SaturnRuntimeQA", "after frame=$it hash=${bridge.saturnAudioFingerprint()}")
                    }
                    if (diagnostics) assertEquals(0, bridge.saveState(handle, File(root, "replay-after-$launch.state").path))
                    assertEquals("Exact 12-frame PCM replay", replayAudio, bridge.saturnAudioFingerprint())
                    assertTrue("Audio frames produced", (output.stats()?.getOrNull(5) ?: 0) > 0)
                    if (game.extension.equals("chd", true)) {
                        assertEquals(2, bridge.probeSegaDisc(game.path))
                        ParcelFileDescriptor.open(game, ParcelFileDescriptor.MODE_READ_ONLY).use {
                            assertEquals(2, bridge.probeSegaDiscDescriptor(it.fd))
                        }
                    }
                } finally {
                    bridge.destroySession(handle)
                    output.release()
                    closeReader(target)
                }
                assertTrue(bridge.nativeSelectCore(1))
                assertEquals(1, bridge.apiVersion())
            }
        } finally {
            bridge.nativeSelectCore(0)
            CoreRuntime.initialize(context)
            consumer.quitSafely()
            consumer.join()
        }
    }
}
