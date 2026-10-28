package com.sbro.emucoreh.core

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SaturnPerformanceBenchmarkInstrumentedTest {
    @Test fun repeatableOwnerRace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val game = File(args.getString("saturnQaGame").orEmpty())
        val seed = File(args.getString("saturnQaState").orEmpty())
        val bios = File(context.filesDir, "saturn/system/saturn_bios.bin")
        assumeTrue("Supply a readable game and gameplay state", game.canRead() && seed.canRead() && bios.canRead())
        val label = args.getString("saturnQaLabel").orEmpty().filter { it.isLetterOrDigit() || it == '-' }.ifBlank { "run" }
        val root = File(context.cacheDir, "saturn-benchmark-qa").apply { mkdirs() }
        val outputDirectory = File(context.getExternalFilesDir(null), "saturn-benchmark-qa").apply { mkdirs() }
        bios.copyTo(File(root, "saturn_bios.bin"), overwrite = true)
        val rawSeed = File(root, "race.raw").apply { writeBytes(requireNotNull(CoreSaveState.unwrap(seed.readBytes(), 3))) }
        val consumer = HandlerThread("SaturnBenchmarkFrames").apply { start() }
        val bridge = CoreRuntime.bridge
        CoreRuntime.shutdown()
        try {
            ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 3).use { reader ->
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
                assertTrue(bridge.nativeSelectCore(3))
                bridge.nativeInit(root.path, root.path, root.path)
                bridge.nativeSetOption("beetle_saturn_midsync", "disabled")
                val handle = bridge.createSession()
                try {
                    assertEquals(0, bridge.setSurface(handle, reader.surface, RendererDefaults.CORE_OPENGL))
                    assertEquals(0, bridge.loadDisc(handle, game.path))
                    assertTrue(bridge.ensureHardwareContext())
                    bridge.runFrame(handle)
                    var expectedAudio: Long? = null
                    var expectedState: ByteArray? = null
                    repeat(2) { iteration ->
                        assertEquals("Restore race state", 0, bridge.loadState(handle, rawSeed.path))
                        bridge.resetAudioQueue()
                        bridge.resetSaturnAudioFingerprint()
                        val start = System.nanoTime()
                        val cpu = android.os.Process.getElapsedCpuTime()
                        repeat(600) { bridge.runFrame(handle) }
                        val wallMs = (System.nanoTime() - start) / 1_000_000
                        val cpuMs = android.os.Process.getElapsedCpuTime() - cpu
                        val audio = bridge.saturnAudioFingerprint()
                        assertTrue(bridge.saturnAudioNonzeroSamples() > 0)
                        val output = File(outputDirectory, "$label-$iteration.state")
                        assertEquals(0, bridge.saveState(handle, output.path))
                        val state = output.readBytes()
                        expectedAudio?.let { assertEquals("Audio replay", it, audio) } ?: run { expectedAudio = audio }
                        expectedState?.let { assertArrayEquals("Full state replay", it, state) } ?: run { expectedState = state }
                        Log.i("SaturnBench", "label=$label iteration=$iteration frames=600 wallMs=$wallMs cpuMs=$cpuMs audio=$audio")
                    }
                } finally { bridge.destroySession(handle) }
            }
        } finally {
            bridge.nativeSelectCore(0)
            consumer.quitSafely()
            consumer.join(3000)
            root.deleteRecursively()
        }
    }
}
