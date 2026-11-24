package com.sbro.emucoreh.core

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Opt-in deterministic regression oracle and throughput benchmark for every core.
 *
 * Boots [oracleGame] on core [oracleCore] (0 Flycast, 1 jgenesis, 2 Supermodel, 3 Saturn)
 * against an offscreen surface, runs [oracleFrames] unpaced frames with an optional scripted
 * pad input and records, every [oracleEvery] frames, the SHA-256 of a full save state (and of
 * the software frame where the frontend keeps one) plus wall/CPU ms per frame. The run is
 * repeated [oracleRuns] times from a fresh boot; all runs must produce identical hashes, so
 * a single invocation also proves the core is deterministic. Results go to
 * `externalFiles/oracle/<label>.json` and to instrumentation status lines prefixed `ORACLE`.
 * Compare JSON from two builds with tools/oracle/compare.py.
 *
 * Input script `oracleInput`: comma separated `from-to:mask` frame ranges (app pad masks,
 * pressed = 1), e.g. `300-305:8,900-1200:16384`.
 */
class CoreOracleInstrumentedTest {
    @Test fun recordsDeterministicCheckpoints() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val gamePath = args.getString("oracleGame").orEmpty()
        assumeTrue("Supply oracleGame", gamePath.isNotBlank())
        val coreId = requireNotNull(args.getString("oracleCore")?.toIntOrNull()) { "oracleCore" }
        require(coreId in 0..3)
        val frames = args.getString("oracleFrames")?.toIntOrNull() ?: 1200
        val every = args.getString("oracleEvery")?.toIntOrNull() ?: 300
        val runs = args.getString("oracleRuns")?.toIntOrNull() ?: 2
        require(frames in 1..72_000 && every in 1..frames && runs in 1..4)
        val renderer = args.getString("oracleRenderer")?.toIntOrNull() ?: RendererDefaults.CORE_OPENGL
        val label = args.getString("oracleLabel").orEmpty()
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "oracle" }
        val options = parseOptions(args.getString("oracleOptions").orEmpty())
        val input = parseInput(args.getString("oracleInput").orEmpty())
        val keepStates = args.getString("oracleKeepStates") == "true"
        // Load every checkpoint state right after saving it: later checkpoints must match a run without reloads.
        val reloadStates = args.getString("oracleReloadStates") == "true"
        val game = File(gamePath)
        assertTrue("Game must be readable: $gamePath", game.canRead())

        val context = instrumentation.targetContext
        val coreRoot = File(context.filesDir, when (coreId) { 1 -> "jgenesis"; 2 -> "supermodel"; 3 -> "saturn"; else -> "flycast" })
        val work = File(context.cacheDir, "core-oracle").apply { deleteRecursively(); mkdirs() }
        // Isolated copy of the installed firmware: the oracle never touches owner data.
        val system = File(work, "system").apply { mkdirs() }
        File(coreRoot, "system").takeIf { it.isDirectory }?.copyRecursively(system, overwrite = true)
        // oracleSystemFiles: `source=>relative/destination;...` extra firmware for the run.
        args.getString("oracleSystemFiles").orEmpty().split(';').filter { it.contains("=>") }.forEach { entry ->
            val (source, destination) = entry.split("=>", limit = 2).map { it.trim() }
            File(source).copyTo(File(system, destination).apply { parentFile!!.mkdirs() }, overwrite = true)
        }
        if (coreId == 2) {
            val xml = File(system, "supermodel/Games.xml").apply { parentFile!!.mkdirs() }
            context.assets.open("supermodel/Games.xml").use { s -> xml.outputStream().use { s.copyTo(it) } }
        }
        val outDir = File(context.getExternalFilesDir(null), "oracle").apply { mkdirs() }

        val runResults = JSONArray()
        var reference: List<String>? = null
        CoreRuntime.shutdown()
        val bridge = CoreRuntime.bridge
        val consumer = HandlerThread("CoreOracleFrames").apply { start() }
        try {
            repeat(runs) { run ->
                val save = File(work, "save-$run").apply { mkdirs() }
                val assets = File(work, "assets-$run").apply { mkdirs() }
                val checkpoints = JSONArray()
                val digests = mutableListOf<String>()
                val captureRequested = java.util.concurrent.atomic.AtomicBoolean(false)
                val captured = java.util.concurrent.atomic.AtomicReference<android.graphics.Bitmap?>(null)
                ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 3).use { reader ->
                    reader.setOnImageAvailableListener({ source ->
                        source.acquireLatestImage()?.use { image ->
                            if (captureRequested.getAndSet(false)) {
                                val plane = image.planes[0]
                                val bitmap = android.graphics.Bitmap.createBitmap(
                                    plane.rowStride / plane.pixelStride, image.height, android.graphics.Bitmap.Config.ARGB_8888)
                                bitmap.copyPixelsFromBuffer(plane.buffer)
                                captured.set(android.graphics.Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height))
                            }
                        }
                    }, Handler(consumer.looper))
                    assertTrue(bridge.nativeSelectCore(coreId))
                    bridge.nativeInit(system.absolutePath, save.absolutePath, assets.absolutePath)
                    options.forEach { (k, v) -> bridge.nativeSetOption(k, v) }
                    bridge.resetAudioQueue()
                    val handle = bridge.createSession()
                    assertTrue(handle != 0L)
                    try {
                        assertEquals(0, bridge.setSurface(handle, reader.surface, renderer))
                        assertEquals("Boot: ${bridge.nativeLastBootError()}", 0, bridge.loadDisc(handle, game.absolutePath))
                        assertTrue("Graphics context", bridge.ensureHardwareContext())
                        var segmentWall = 0L
                        var segmentCpu = 0L
                        var appliedMask = -1
                        for (frame in 1..frames) {
                            val mask = input.firstOrNull { frame in it.first }?.second ?: 0
                            if (mask != appliedMask) {
                                bridge.setPadButtons(handle, 0, 0xffff xor mask)
                                appliedMask = mask
                            }
                            val checkpoint = frame % every == 0 || frame == frames
                            if (checkpoint) { captured.set(null); captureRequested.set(true) }
                            val cpu0 = Debug.threadCpuTimeNanos()
                            val wall0 = System.nanoTime()
                            bridge.runFrame(handle)
                            segmentWall += System.nanoTime() - wall0
                            segmentCpu += Debug.threadCpuTimeNanos() - cpu0
                            if (frame % 64 == 0) bridge.resetAudioQueue()
                            if (checkpoint) {
                                // Presented (GPU) frame from the ImageReader: hash + PNG for review.
                                val deadline = System.nanoTime() + 1_000_000_000L
                                while (captured.get() == null && System.nanoTime() < deadline) Thread.sleep(2)
                                val presented = captured.getAndSet(null)
                                captureRequested.set(false)
                                val presentedHash = presented?.let { bitmap ->
                                    val buffer = java.nio.ByteBuffer.allocate(bitmap.byteCount)
                                    bitmap.copyPixelsToBuffer(buffer)
                                    if (run == 0) File(outDir, "$label-f$frame.png").outputStream().use {
                                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                                    }
                                    bitmap.recycle()
                                    sha256(buffer.array())
                                }
                                val segmentFrames = if (frame % every == 0) every else frame % every
                                val state = File(work, "state.bin")
                                assertEquals("saveState at $frame", 0, bridge.saveState(handle, state.absolutePath))
                                val stateHash = sha256(state.readBytes())
                                if (reloadStates)
                                    assertEquals("loadState at $frame", 0, bridge.loadState(handle, state.absolutePath))
                                if (keepStates) state.copyTo(File(outDir, "$label-r$run-f$frame.state"), overwrite = true)
                                val frameHash = bridge.captureSoftwareFrame(handle)?.let { pixels ->
                                    val bytes = java.nio.ByteBuffer.allocate(pixels.size * 4)
                                    bytes.asIntBuffer().put(pixels)
                                    sha256(bytes.array())
                                }
                                digests += "$frame:$stateHash:${frameHash.orEmpty()}"
                                val point = JSONObject()
                                    .put("frame", frame)
                                    .put("state", stateHash)
                                    .put("frameHash", frameHash ?: JSONObject.NULL)
                                    .put("presented", presentedHash ?: JSONObject.NULL)
                                    .put("wallMsPerFrame", segmentWall / 1e6 / segmentFrames)
                                    .put("cpuMsPerFrame", segmentCpu / 1e6 / segmentFrames)
                                checkpoints.put(point)
                                val line = String.format(Locale.US,
                                    "ORACLE label=%s run=%d frame=%d state=%s frame_hash=%s img=%s wall_ms=%.3f cpu_ms=%.3f",
                                    label, run, frame, stateHash.take(16), frameHash?.take(16) ?: "-", presentedHash?.take(16) ?: "-",
                                    segmentWall / 1e6 / segmentFrames, segmentCpu / 1e6 / segmentFrames)
                                Log.i(TAG, line)
                                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$line\n") })
                                segmentWall = 0L
                                segmentCpu = 0L
                            }
                        }
                    } finally {
                        bridge.destroySession(handle)
                    }
                }
                runResults.put(JSONObject().put("run", run).put("checkpoints", checkpoints))
                reference?.let { expected ->
                    assertEquals("Run $run diverged from run 0 (core is not deterministic)", expected, digests)
                } ?: run { reference = digests }
            }
            val result = JSONObject()
                .put("label", label)
                .put("core", coreId)
                .put("game", game.name)
                .put("frames", frames)
                .put("options", JSONObject(options))
                .put("input", args.getString("oracleInput").orEmpty())
                .put("device", android.os.Build.MODEL)
                .put("runs", runResults)
            File(outDir, "$label.json").writeText(result.toString(2))
        } finally {
            consumer.quitSafely()
            consumer.join(3000)
            assertTrue(bridge.nativeSelectCore(0))
            work.deleteRecursively()
            CoreRuntime.initialize(context)
        }
    }

    private fun parseOptions(spec: String): Map<String, String> = spec.split(';')
        .mapNotNull { entry -> entry.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() } }
        .associate { it[0].trim() to it[1].trim() }

    private fun parseInput(spec: String): List<Pair<IntRange, Int>> = spec.split(',')
        .filter { it.isNotBlank() }
        .map { entry ->
            val (range, mask) = entry.split(':', limit = 2)
            val (from, to) = range.split('-', limit = 2).map { it.trim().toInt() }
            from..to to mask.trim().toInt()
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object { const val TAG = "CoreOracle" }
}
