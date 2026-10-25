package com.sbro.emucoreh.core

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Bundle
import android.os.Debug
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

/**
 * Opt-in frontend throughput/tail benchmark, NOT display cadence: ImageReader drains immediately.
 * Requires app-readable jgenesisBenchmarkRom AND jgenesisBenchmarkState (raw libretro state).
 * No CoreRuntime loop, limiter, or AAudio output is active. The native PCM ring still receives audio.
 * A no-surface baseline cannot use runFrame(): that API requires a hardware context before retro_run.
 */
class JgenesisRenderingPerformanceInstrumentedTest {
    private data class Proof(val width: Int, val height: Int, val pixels: IntArray)

    @Test fun comparesFrontendFrameTailsFromGameplayState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val romPath = args.getString("jgenesisBenchmarkRom")
        assumeTrue("Supply jgenesisBenchmarkRom to opt in", !romPath.isNullOrBlank())
        val rom = File(requireNotNull(romPath))
        val state = File(requireNotNull(args.getString("jgenesisBenchmarkState")) {
            "An explicit gameplay state is mandatory; boot-only measurements are not allowed"
        })
        assertTrue("Owner ROM must be readable", rom.isFile && rom.canRead())
        assertTrue("Gameplay state must be readable and nonempty", state.isFile && state.canRead() && state.length() > 0)
        fun integer(key: String, default: Int, range: IntRange): Int =
            (args.getString(key)?.toInt() ?: default).also { require(it in range) { "$key outside $range" } }
        val width = integer("jgenesisBenchmarkWidth", 2412, 64..4096)
        val height = integer("jgenesisBenchmarkHeight", 1080, 64..4096)
        val warmup = integer("jgenesisBenchmarkWarmupFrames", 30, 0..3600)
        val frames = integer("jgenesisBenchmarkFrames", 360, 60..3600)
        val samples = integer("jgenesisBenchmarkSamples", 3, 1..10)
        val modes = (args.getString("jgenesisBenchmarkRenderers") ?: "opengl,vulkan")
            .split(',').map { it.trim() }.also {
                require(it.isNotEmpty() && it.distinct().size == it.size && it.all { mode -> mode in listOf("opengl", "vulkan") })
            }
        val context = instrumentation.targetContext
        val outputRoot = File(context.cacheDir, "jgenesis-rendering-performance-qa").apply { mkdirs() }
        val system = File(context.filesDir, "jgenesis/system")
        val saves = File(outputRoot, "save").apply { mkdirs() }
        fun report(message: String) {
            Log.i("JgenesisRenderingQA", message)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
        }
        CoreRuntime.shutdown()
        val bridge = CoreRuntime.bridge
        try {
            report("offscreen=true unpaced=true aaudio=false noSurface=unsupported_by_runFrame rom=${rom.name} state=${state.name} stateBytes=${state.length()} surface=${width}x$height warmup=$warmup frames=$frames samples=$samples")
            for (mode in modes) {
                assertTrue(bridge.nativeSelectCore(1))
                bridge.nativeInit(system.absolutePath, saves.absolutePath, outputRoot.absolutePath)
                bridge.nativeSetOption("jgenesis_region", "auto")
                bridge.nativeSetOption("jgenesis_timing", "ntsc")
                bridge.nativeSetOption("jgenesis_jit_backend", "interpreter")
                bridge.nativeSetShaderEffect(0)
                bridge.nativeSetShaderPreset("", false)
                bridge.setTimeControl(0)
                bridge.setFrameSkip(0)
                bridge.setDisplayAspectRatio(1)
                bridge.setDisplayCrop(0, 0, 0, 0)
                bridge.resetAudioQueue()
                val session = bridge.createSession()
                assertTrue("Session creation", session != 0L)
                val consumer = HandlerThread("JgenesisBenchmarkConsumer").apply { start() }
                val consumed = AtomicInteger()
                val proofArmed = AtomicBoolean(false)
                val proof = AtomicReference<Proof>()
                val latestGpuFrame = AtomicReference<Proof>()
                val gpuMetadata = AtomicReference("no ImageReader buffer observed after proof armed")
                val consumerFailure = AtomicReference<Throwable>()
                val proofReady = CountDownLatch(1)
                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
                reader.setOnImageAvailableListener({ source ->
                    try {
                        source.acquireLatestImage()?.use { image ->
                            consumed.incrementAndGet()
                            if (proofArmed.get() && proof.get() == null) {
                                val plane = image.planes[0]
                                val buffer = plane.buffer
                                val base = buffer.position()
                                gpuMetadata.set("image=${image.width}x${image.height} format=${image.format} planes=${image.planes.size} crop=${image.cropRect} rowStride=${plane.rowStride} pixelStride=${plane.pixelStride} bufferPosition=$base limit=${buffer.limit()} capacity=${buffer.capacity()}")
                                val pixels = IntArray(image.width * image.height)
                                for (y in 0 until image.height) for (x in 0 until image.width) {
                                    val offset = base + y * plane.rowStride + x * plane.pixelStride
                                    val r = buffer.get(offset).toInt() and 255
                                    val g = buffer.get(offset + 1).toInt() and 255
                                    val b = buffer.get(offset + 2).toInt() and 255
                                    pixels[y * image.width + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                                }
                                val frame = Proof(image.width, image.height, pixels)
                                latestGpuFrame.set(frame)
                                // Context setup can enqueue a uniform black buffer. Keep draining
                                // it and wait for actual scene pixels instead of latching it forever.
                                if (pixels.any { it != pixels[0] }) {
                                    proof.set(frame)
                                    proofReady.countDown()
                                }
                            }
                        }
                    } catch (error: Throwable) {
                        consumerFailure.set(error)
                        proofReady.countDown()
                    }
                }, Handler(consumer.looper))
                try {
                    val renderer = if (mode == "opengl") RendererDefaults.CORE_OPENGL else RendererDefaults.CORE_VULKAN
                    assertEquals("Requested renderer must initialize without silent fallback: $mode", 0,
                        bridge.setSurface(session, reader.surface, renderer))
                    assertEquals("ROM boot: ${bridge.nativeLastBootError()}", 0, bridge.loadDisc(session, rom.absolutePath))
                    assertTrue("$mode context", bridge.ensureHardwareContext())
                    assertEquals("Gameplay state must load before proof", 0, bridge.loadState(session, state.absolutePath))
                    // Drain setup buffers on their owning consumer thread before arming proof.
                    // No emulation frame is submitted until that barrier has completed.
                    val armed = CountDownLatch(1)
                    assertTrue(Handler(consumer.looper).post {
                        reader.acquireLatestImage()?.close()
                        proofArmed.set(true)
                        armed.countDown()
                    })
                    assertTrue("$mode proof consumer must arm", armed.await(5, TimeUnit.SECONDS))
                    var preflightFrames = 0
                    while (proofReady.count > 0 && preflightFrames < 8) {
                        bridge.runFrame(session)
                        preflightFrames++
                        proofReady.await(250, TimeUnit.MILLISECONDS)
                    }
                    val hasProof = proofReady.await(5, TimeUnit.SECONDS)
                    // Persist both sides before asserting so a blank GPU frame is actionable.
                    // These diagnostics never enter a measured sample.
                    report("renderer=$mode preflightFrames=$preflightFrames consumed=${consumed.get()} metadata=${gpuMetadata.get()} consumerFailure=${consumerFailure.get()}")
                    report("renderer=$mode nativeDiagnostics=${bridge.getDiagnostics()} displayRect=${bridge.getDisplayRect(session)?.contentToString()} audioStats=${bridge.audioOutputStats(0)?.contentToString()}")
                    File(outputRoot, "$mode-diagnostics.txt").writeText(
                        "${gpuMetadata.get()}\n${bridge.getDiagnostics()}\nconsumerFailure=${consumerFailure.get()}\n")
                    latestGpuFrame.get()?.let { frame ->
                        val gpuFile = File(outputRoot, "$mode-gpu-latest.png")
                        writeProof(frame, gpuFile)
                        report("renderer=$mode gpuLatest=${gpuFile.absolutePath} firstArgb=${Integer.toHexString(frame.pixels[0])} nonuniform=${frame.pixels.any { it != frame.pixels[0] }}")
                    }
                    val cpuFrame = bridge.captureSoftwareFrame(session)
                    if (cpuFrame == null) {
                        report("renderer=$mode cpuSnapshot=null (current native Vulkan path may not retain one)")
                    } else {
                        val cpuBitmap = requireNotNull(createSoftwareSaveStatePreviewBitmap(cpuFrame))
                        val cpuFile = File(outputRoot, "$mode-cpu-proof.png")
                        try {
                            cpuFile.outputStream().use { assertTrue(cpuBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        } finally { cpuBitmap.recycle() }
                        report("renderer=$mode cpuProof=${cpuFile.absolutePath} source=${cpuFrame[0]}x${cpuFrame[1]} nonuniform=${(2 until cpuFrame.size).any { cpuFrame[it] != cpuFrame[2] }}")
                    }
                    assertTrue("$mode must deliver nonuniform gameplay frame proof after state load", hasProof)
                    consumerFailure.get()?.let { throw AssertionError("Image consumer failed", it) }
                    val captured = requireNotNull(proof.get())
                    assertTrue("Gameplay proof must contain nonuniform RGB pixels", captured.pixels.any { it != captured.pixels[0] })
                    val png = File(outputRoot, "$mode-proof.png")
                    writeProof(captured, png)
                    var bestMean = Double.POSITIVE_INFINITY
                    repeat(samples) { sample ->
                        // Restore the exact same scene, including before every renderer/sample warmup.
                        assertEquals("Gameplay state reload $mode sample=$sample", 0, bridge.loadState(session, state.absolutePath))
                        bridge.resetAudioQueue()
                        repeat(warmup) { bridge.runFrame(session) }
                        val sourceBefore = requireNotNull(bridge.audioOutputStats(0))[5]
                        val deliveredBefore = consumed.get()
                        val wall = LongArray(frames)
                        val cpuBefore = Debug.threadCpuTimeNanos()
                        val runBefore = System.nanoTime()
                        repeat(frames) { frame ->
                            val before = System.nanoTime()
                            bridge.runFrame(session)
                            wall[frame] = System.nanoTime() - before
                        }
                        val elapsed = System.nanoTime() - runBefore
                        val cpu = Debug.threadCpuTimeNanos() - cpuBefore
                        val stats = requireNotNull(bridge.audioOutputStats(0))
                        assertTrue("$mode produced real PCM", stats[5] > sourceBefore)
                        assertTrue("$mode consumer remains live", consumerFailure.get() == null)
                        assertTrue("$mode must deliver images during measured gameplay", consumed.get() > deliveredBefore)
                        val sorted = wall.sorted()
                        fun percentile(percent: Double): Double = sorted[(ceil(percent * frames).toInt() - 1).coerceIn(sorted.indices)] / 1_000_000.0
                        val mean = elapsed / frames / 1_000_000.0
                        bestMean = minOf(bestMean, mean)
                        val csv = File(outputRoot, "$mode-sample-$sample.csv")
                        csv.bufferedWriter().use { writer ->
                            writer.appendLine("frame,wallNanos")
                            wall.forEachIndexed { index, nanos -> writer.appendLine("$index,$nanos") }
                        }
                        report(String.format(Locale.US,
                            "renderer=%s sample=%d meanMs=%.3f p50Ms=%.3f p95Ms=%.3f p99Ms=%.3f maxMs=%.3f threadCpuMsPerFrame=%.3f fps=%.2f over16_67ms=%d pcmFrames=%d consumedDuring=%d csv=%s proof=%s",
                            mode, sample, mean, percentile(.50), percentile(.95), percentile(.99), percentile(1.0),
                            cpu / frames / 1_000_000.0, frames * 1_000_000_000.0 / elapsed,
                            wall.count { it > 16_666_667L }, stats[5] - sourceBefore, consumed.get() - deliveredBefore,
                            csv.absolutePath, png.absolutePath))
                    }
                    report(String.format(Locale.US, "renderer=%s bestMeanMs=%.3f", mode, bestMean))
                } finally {
                    bridge.destroySession(session)
                    reader.close()
                    consumer.quitSafely()
                    consumer.join(10_000)
                    assertTrue("Image consumer must stop", !consumer.isAlive)
                }
            }
        } finally {
            assertTrue(bridge.nativeSelectCore(0))
            CoreRuntime.initialize(context)
        }
    }

    private fun writeProof(frame: Proof, file: File) {
        val bitmap = Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
