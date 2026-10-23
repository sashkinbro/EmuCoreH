package com.sbro.emucoreh.core

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Model 3 native QA. Only failure/recovery fixtures are authored here; retail ROMs stay on device. */
@RunWith(AndroidJUnit4::class)
class Model3RuntimeInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val bridge get() = CoreRuntime.bridge

    @Test fun missingAndInvalidSetsFailClearlyAndLeaveTheOtherCoresUsable() {
        val qa = newQaDirectory("failures")
        CoreRuntime.shutdown()
        try {
            assertTrue("Select Model 3", bridge.nativeSelectCore(2))
            initializeIsolated(qa, copyDefinitions = false)
            var handle = bridge.createSession()
            assertNotEquals(0L, handle)
            try {
                assertNotEquals(0, bridge.loadDisc(handle, File(qa, "missing.zip").absolutePath))
                assertTrue("Missing path reason: ${bridge.nativeLastBootError()}",
                    bridge.nativeLastBootError().orEmpty().contains("missing", true))
                val emptySet = File(qa, "authored-invalid.zip").apply {
                    ZipOutputStream(outputStream()).use { zip ->
                        zip.putNextEntry(ZipEntry("fixture.txt"))
                        zip.write("Authored QA fixture, no ROM data".toByteArray())
                        zip.closeEntry()
                    }
                }
                assertNotEquals(0, bridge.loadDisc(handle, emptySet.absolutePath))
                assertTrue("Missing definitions reason: ${bridge.nativeLastBootError()}",
                    bridge.nativeLastBootError().orEmpty().contains("Games.xml"))
                bridge.destroySession(handle)
                handle = 0
                initializeIsolated(qa, copyDefinitions = true)
                handle = bridge.createSession()
                assertNotEquals(0, bridge.loadDisc(handle, emptySet.absolutePath))
                assertTrue("Invalid archive reason: ${bridge.nativeLastBootError()}",
                    bridge.nativeLastBootError().orEmpty().let {
                        it.contains("ROM", true) && (it.contains("unsupported", true) || it.contains("incomplete", true))
                    })
                assertNotEquals("Failed boot has no serializable game", 0,
                    bridge.saveState(handle, File(qa, "invalid.rawstate").absolutePath))
            } finally {
                if (handle != 0L) bridge.destroySession(handle)
            }
            exerciseJgenesisAndFlycastAfterModel3(qa)
        } finally {
            restoreFrontend()
        }
    }

    @Test fun ownerSuppliedSegaRally2RendersAudioAndRestores() = exerciseOwnerSet("srally2")
    @Test fun ownerSuppliedDaytona2RendersAudioAndRestores() = exerciseOwnerSet("daytona2")
    @Test fun ownerSuppliedLostWorldRendersAudioAndRestores() = exerciseOwnerSet("lostwsga")

    @Test fun ownerSuppliedSelectedSetPreparesRendersAndRestores() {
        val set = InstrumentationRegistry.getArguments().getString("model3QaSet")
        assumeTrue("Supply model3QaSet for an additional owner ROM", !set.isNullOrBlank())
        requireNotNull(Model3Romsets.find("$set.zip")) { "Unrecognized Model 3 set" }
        exerciseOwnerSet(requireNotNull(set))
    }

    private fun exerciseOwnerSet(set: String) {
        val args = InstrumentationRegistry.getArguments()
        val root = File(args.getString("model3QaRoot")
            ?: File(context.getExternalFilesDir(null), "qa-model3-romset").absolutePath)
        val rom = File(args.getString("model3QaPath_$set") ?: File(root, "$set.zip").absolutePath)
        assumeTrue("Owner-supplied $set.zip is absent or unreadable: $rom", rom.isFile && rom.canRead())
        val qa = newQaDirectory(set)
        val nativeSet = requireNotNull(Model3ContentPreparer.resolveExpectedSet(context, rom.absolutePath)) {
            "Selected Model 3 program revision is incomplete or ambiguous"
        }
        val targetFrames = args.getString("model3QaFrames")?.toIntOrNull()?.coerceIn(120, 3600) ?: 600
        val timeoutSeconds = args.getString("model3QaTimeoutSeconds")?.toLongOrNull()?.coerceIn(30, 900) ?: 300
        val scriptedSteps = args.getString("model3QaServiceScript")?.split(',')?.map { step ->
            val parts = step.split(':')
            require(parts.size == 2)
            parts[0].toInt().also { require(it in 0..65535) } to
                parts[1].toInt().also { require(it in 1..3600) }
        }
        val scriptedInputs = scriptedSteps?.flatMap { (buttons, frames) -> List(frames) { buttons } }
        require(scriptedInputs == null || scriptedInputs.size <= targetFrames)
        val stepEnds = scriptedSteps?.runningFold(0) { sum, step -> sum + step.second }?.drop(1).orEmpty()
        val report = mutableListOf("game=$set", "rom=${rom.absolutePath}", "qa=${qa.absolutePath}")
        CoreRuntime.shutdown()
        try {
            assertTrue("Select Model 3", bridge.nativeSelectCore(2))
            initializeIsolated(qa, copyDefinitions = true)
            args.getString("model3QaNvram_$set")?.let { path ->
                val source = File(path)
                assertTrue("Owner cabinet NVRAM must be readable", source.isFile && source.canRead())
                val destination = File(qa, "save/supermodel/$nativeSet/nvram.bin")
                destination.parentFile!!.mkdirs()
                source.copyTo(destination, overwrite = true)
            }
            var handle = bridge.createSession()
            assertNotEquals(0L, handle)
            try {
                PixelRecorder("Model3-$set").use { pixels ->
                    try {
                        assertEquals(0, bridge.setSurface(handle, pixels.reader.surface, RendererDefaults.CORE_OPENGL))
                        assertTrue("Create EGL context", bridge.ensureHardwareContext())
                        assertEquals("Boot $set: ${bridge.nativeLastBootError()}",
                            0, bridge.loadDisc(handle, Model3ContentPreparer.prepare(context, rom.absolutePath)))
                        assertTrue("Bind Model 3 GLES renderer: ${bridge.nativeLastBootError()}", bridge.ensureHardwareContext())
                        assertTrue(bridge.getSystemInfo().contains("Supermodel", true))
                        assertFalse("Loaded Model 3 owns its core", bridge.nativeSelectCore(1))
                        assertFalse("Arcade set has no disc tray", bridge.hasDiscMedia(handle))
                        args.getString("model3QaLoadStateForProbe")?.let { path ->
                            assertEquals("Restore cabinet diagnostics", 0, bridge.loadState(handle, path))
                        }
                        val before = requireNotNull(bridge.audioOutputStats(0))
                        val started = System.nanoTime()
                        val deadline = started + TimeUnit.SECONDS.toNanos(timeoutSeconds)
                        repeat(targetFrames) { frame ->
                            assertTrue("$set exceeded ${timeoutSeconds}s after $frame frames", System.nanoTime() < deadline)
                            // Edges are separated by release frames; hold accelerator after boot.
                            val buttons = scriptedInputs?.getOrElse(frame) { 0 } ?: when {
                                frame % 120 in 60..61 -> 1 shl 0 // Frontend Select -> libretro Coin (2)
                                frame % 120 in 75..76 -> 1 shl 3 // Start
                                frame > 180 && set != "lostwsga" -> 1 shl 9 // Frontend R2 -> libretro accelerator (13)
                                else -> 0
                            }
                            bridge.setPadButtons(handle, 0, buttons.inv() and 0xffff)
                            bridge.runFrame(handle)
                            if (frame + 1 in stepEnds) pixels.writePng(File(qa, "step-${stepEnds.indexOf(frame+1)}.png"))
                            if (frame % 120 == 119) Log.i("Model3RuntimeQA", "$set frames=${frame+1} presented=${pixels.frames.get()} visible=${pixels.sawVisible.get()}")
                        }
                        bridge.setPadButtons(handle, 0, 0xffff)
                        assertTrue("No presented Model 3 images: ${bridge.getDiagnostics()}", pixels.presented.await(5, TimeUnit.SECONDS))
                        pixels.failure.get()?.let { throw AssertionError("ImageReader failed", it) }
                        pixels.writePng(File(qa, "$set.png"))
                        if (scriptedInputs != null) assertEquals("Capture cabinet setup diagnostics", 0,
                            bridge.saveState(handle, File(qa, "service.rawstate").absolutePath))
                        assertTrue("Only blank/uniform pixels after $targetFrames frames: ${bridge.getDiagnostics()}", pixels.sawVisible.get())
                        assertTrue("Expected a color game scene after $targetFrames frames; sampled colored pixels=${pixels.latestColoredPixels.get()}",
                            pixels.latestColoredPixels.get() >= 100)
                        val after = requireNotNull(bridge.audioOutputStats(0))
                        assertTrue("Guest video callbacks must advance", after[9] > before[9])
                        assertTrue("Guest must produce PCM source frames", after[5] > before[5])
                        verifyStateRoundTrip(handle, qa)
                        report += "result=PASS"
                        report += "presentedFrames=${pixels.frames.get()}"
                        report += "sourceVideoFrames=${after[9]-before[9]}"
                        report += "sourceAudioFrames=${after[5]-before[5]}"
                        report += "elapsedSeconds=${(System.nanoTime()-started)/1e9}"
                    } finally {
                        // The same instrumentation thread owns every EGL call.
                        bridge.destroySession(handle)
                        bridge.setSurface(handle, null, RendererDefaults.CORE_OPENGL)
                        handle = 0
                    }
                }
            } finally {
                if (handle != 0L) bridge.destroySession(handle)
            }
            exerciseJgenesisAndFlycastAfterModel3(qa)
            assertTrue("Shutdown persists the verified set's isolated NVRAM",
                File(qa, "save/supermodel/$nativeSet/nvram.bin").isFile)
        } catch (error: Throwable) {
            report += "result=FAIL ${error.javaClass.simpleName}: ${error.message}"
            throw error
        } finally {
            File(qa, "report.txt").writeText(report.joinToString("\n") + "\n")
            Log.i("Model3RuntimeQA", report.joinToString("; "))
            restoreFrontend()
            Model3ContentPreparer.releasePreparedContent(context)
        }
    }

    private fun verifyStateRoundTrip(handle: Long, qa: File) {
        val saved = File(qa, "model3.rawstate")
        val check = File(qa, "model3-restored.rawstate")
        assertEquals("Serialize Model 3", 0, bridge.saveState(handle, saved.absolutePath))
        assertTrue(saved.length() > 84)
        val expected = saved.readBytes()
        bridge.resetModel3AudioFingerprint()
        val expectedAudioStart = requireNotNull(bridge.audioOutputStats(0))[5]
        repeat(12) { bridge.runFrame(handle) }
        val expectedAudio = bridge.model3AudioFingerprint()
        val expectedAudioFrames = requireNotNull(bridge.audioOutputStats(0))[5] - expectedAudioStart
        val zeroReference = silenceFingerprint(expectedAudioFrames)
        val replayAudible = expectedAudio != zeroReference
        val future = File(qa, "model3-future.rawstate")
        assertEquals("Capture the deterministic 12-frame future", 0, bridge.saveState(handle, future.absolutePath))
        assertEquals("Restore Model 3", 0, bridge.loadState(handle, saved.absolutePath))
        assertEquals(0, bridge.saveState(handle, check.absolutePath))
        assertArrayEquals("A paused restore must recover the complete deterministic state", expected, check.readBytes())
        val corrupt = File(qa, "model3-corrupt.rawstate")
        corrupt.writeBytes(expected.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 0x40).toByte() })
        assertNotEquals("Reject corrupt checksum", 0, bridge.loadState(handle, corrupt.absolutePath))
        assertEquals(0, bridge.saveState(handle, check.absolutePath))
        assertArrayEquals("Rejected state cannot mutate the running guest", expected, check.readBytes())
        bridge.resetModel3AudioFingerprint()
        repeat(12) { bridge.runFrame(handle) }
        val actualAudio = bridge.model3AudioFingerprint()
        File(qa, "audio-replay.txt").writeText("expected=$expectedAudio\nactual=$actualAudio\n" +
            "sourceFrames=$expectedAudioFrames\nzeroReference=$zeroReference\nreplayAudible=$replayAudible\n")
        Log.i("Model3RuntimeQA", "12-frame audio replay expected=$expectedAudio actual=$actualAudio sourceFrames=$expectedAudioFrames replayAudible=$replayAudible")
        assertEquals(0, bridge.saveState(handle, check.absolutePath))
        assertFalse("Guest executes again after restoration", expected.contentEquals(check.readBytes()))
        assertArrayEquals("Replaying the same 12 frames restores identical future guest state",
            future.readBytes(), check.readBytes())
        assertEquals("Replaying the same 12 frames must reproduce exact stereo PCM",
            expectedAudio, actualAudio)
        if (InstrumentationRegistry.getArguments().getString("model3QaRequireAudible") == "true") {
            assertTrue("The selected replay window must contain actual nonzero PCM", replayAudible)
        }

        // A held shift restored after a release must not be interpreted as a fresh edge.
        val held = File(qa, "model3-held-shift.rawstate")
        val heldFuture = File(qa, "model3-held-shift-future.rawstate")
        val up = (1 shl 11).inv() and 0xffff // frontend R -> libretro shift up
        bridge.setPadButtons(handle, 0, up)
        bridge.runFrame(handle)
        assertEquals(0, bridge.saveState(handle, held.absolutePath))
        bridge.runFrame(handle)
        assertEquals(0, bridge.saveState(handle, heldFuture.absolutePath))
        bridge.setPadButtons(handle, 0, 0xffff)
        repeat(2) { bridge.runFrame(handle) }
        assertEquals(0, bridge.loadState(handle, held.absolutePath))
        bridge.setPadButtons(handle, 0, up)
        bridge.runFrame(handle)
        assertEquals(0, bridge.saveState(handle, check.absolutePath))
        assertArrayEquals("Restoring a held shift cannot add another gear edge",
            heldFuture.readBytes(), check.readBytes())
        bridge.setPadButtons(handle, 0, 0xffff)
    }

    private fun silenceFingerprint(sourceFrames: Long): Long {
        check(sourceFrames in 1..250_000) { "Unexpected replay PCM frame count: $sourceFrames" }
        var hash = 14695981039346656037uL
        repeat((sourceFrames * 4).toInt()) { hash *= 1099511628211uL }
        return hash.toLong()
    }

    private fun exerciseJgenesisAndFlycastAfterModel3(qa: File) {
        assertTrue("Select jgenesis after Model 3 shutdown", bridge.nativeSelectCore(1))
        initializeIsolated(File(qa, "jgenesis"), copyDefinitions = false)
        val rom = File(qa, "authored-recovery.md").apply { writeBytes(genesisRom()) }
        var handle = bridge.createSession()
        assertNotEquals(0L, handle)
        try {
            PixelRecorder("Model3Recovery").use { pixels ->
                try {
                    assertEquals(0, bridge.setSurface(handle, pixels.reader.surface, RendererDefaults.CORE_OPENGL))
                    assertEquals("jgenesis survives Model 3 teardown", 0, bridge.loadDisc(handle, rom.absolutePath))
                    repeat(15) { bridge.runFrame(handle) }
                    assertTrue(pixels.presented.await(5, TimeUnit.SECONDS))
                    val frame = requireNotNull(bridge.captureSoftwareFrame(handle))
                    assertTrue("Authored MD fixture rendered actual colored pixels", frame.drop(2).any { it and 0x00ffffff != 0 })
                    assertTrue(bridge.getSystemInfo().contains("jgenesis", true))
                } finally {
                    bridge.destroySession(handle)
                    bridge.setSurface(handle, null, RendererDefaults.CORE_OPENGL)
                    handle = 0
                }
            }
        } finally {
            if (handle != 0L) bridge.destroySession(handle)
        }
        assertTrue("Flycast remains selectable after both other engines", bridge.nativeSelectCore(0))
        assertEquals(1, bridge.apiVersion())
        assertTrue(bridge.getSystemInfo().contains("Flycast", true))
    }

    private fun initializeIsolated(qa: File, copyDefinitions: Boolean) {
        val system = File(qa, "system").apply { mkdirs() }
        val save = File(qa, "save").apply { mkdirs() }
        val assets = File(qa, "assets").apply { mkdirs() }
        if (copyDefinitions) {
            val xml = File(system, "supermodel/Games.xml").apply { parentFile!!.mkdirs() }
            context.assets.open("supermodel/Games.xml").use { source -> xml.outputStream().use { target -> source.copyTo(target) } }
        }
        bridge.nativeInit(system.absolutePath, save.absolutePath, assets.absolutePath)
        bridge.nativeSetShaderPreset("", false)
        bridge.nativeSetShaderEffect(0)
        bridge.setFrameSkip(0)
        bridge.setDisplayCrop(0, 0, 0, 0)
        bridge.setDisplayAspectRatio(1)
        bridge.setTimeControl(0)
        bridge.resetAudioQueue()
    }

    private fun restoreFrontend() {
        bridge.resetAudioQueue()
        assertTrue(bridge.nativeSelectCore(0))
        CoreRuntime.initialize(context)
    }

    private fun newQaDirectory(name: String) = File(context.cacheDir,
        "model3-runtime-qa/$name-${System.nanoTime()}").apply { check(mkdirs()) }

    private class PixelRecorder(name: String) : AutoCloseable {
        private val consumer = HandlerThread(name).apply { start() }
        private val handler = Handler(consumer.looper)
        val reader = ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 3)
        val frames = AtomicInteger()
        val sawVisible = AtomicBoolean()
        val latestColoredPixels = AtomicInteger()
        val presented = CountDownLatch(3)
        val failure = AtomicReference<Throwable>()
        private val rgba = AtomicReference<ByteArray>()
        init {
            reader.setOnImageAvailableListener({ source ->
                try {
                    source.acquireLatestImage()?.use { image ->
                        presented.countDown()
                        if (frames.incrementAndGet() % 3 == 0) {
                            val plane = image.planes[0]
                            check(plane.pixelStride == 4)
                            val pixels = ByteArray(image.width * image.height * 4)
                            val buffer = plane.buffer.duplicate()
                            for (row in 0 until image.height) {
                                buffer.position(row * plane.rowStride)
                                buffer.get(pixels, row * image.width * 4, image.width * 4)
                            }
                            val colors = HashSet<Int>()
                            var nonBlack = 0
                            var colored = 0
                            for (offset in pixels.indices step 64) {
                                val red = pixels[offset].toInt() and 255
                                val green = pixels[offset+1].toInt() and 255
                                val blue = pixels[offset+2].toInt() and 255
                                val rgb = (red shl 16) or (green shl 8) or blue
                                colors += rgb
                                if (rgb != 0) nonBlack++
                                if (maxOf(red, green, blue) >= 32 && maxOf(red, green, blue) - minOf(red, green, blue) >= 24) colored++
                            }
                            if (colors.size >= 8 && nonBlack >= 100) sawVisible.set(true)
                            latestColoredPixels.set(colored)
                            rgba.set(pixels)
                        }
                    }
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
            }, handler)
        }
        fun writePng(file: File) {
            val pixels = rgba.get() ?: return // An early scripted key edge may precede image three.
            val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            try {
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
        override fun close() {
            reader.setOnImageAvailableListener(null, null)
            val drained = CountDownLatch(1)
            handler.post { drained.countDown() }
            drained.await(5, TimeUnit.SECONDS)
            reader.close()
            consumer.quitSafely()
            consumer.join(5_000)
        }
    }

    private fun genesisRom(): ByteArray = ByteArray(32768).apply {
        byteArrayOf(0, 0xff.toByte(), 0xff.toByte(), 0xf0.toByte(), 0, 0, 2, 0).copyInto(this)
        "SEGA MEGA DRIVE ".toByteArray().copyInto(this, 0x100)
        this[0x1f0] = 'U'.code.toByte()
        // Set CRAM color 0 red, enable the display, then idle.
        byteArrayOf(0x23,0xfc.toByte(),0xc0.toByte(),0,0,0,0,0xc0.toByte(),0,4,
            0x33,0xfc.toByte(),0,0x0e,0,0xc0.toByte(),0,0,
            0x33,0xfc.toByte(),0x81.toByte(),0x44,0,0xc0.toByte(),0,4,
            0x4e,0x72,0x27,0,0x60,0xfa.toByte()).copyInto(this,0x200)
    }
}
