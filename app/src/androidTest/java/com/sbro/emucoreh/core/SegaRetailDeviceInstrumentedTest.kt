package com.sbro.emucoreh.core

import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.nio.ByteBuffer
import com.sbro.emucoreh.data.AppPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

/** Optional device matrix: content is supplied by the device owner and is never bundled. */
class SegaRetailDeviceInstrumentedTest {
    @Test fun ownerSuppliedGamesBootPresentAndRestoreAcrossAllSixPlatforms() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(InstrumentationRegistry.getArguments().getString("segaQaRoot") ?: "/storage/emulated/0/Sega")
        assumeTrue("Owner-supplied test folder is not accessible", root.isDirectory && root.canRead())
        val candidates = root.walkTopDown().maxDepth(3).filter { it.isFile }.toList()
        val arguments = InstrumentationRegistry.getArguments()
        val requestedSystems = arguments.getString("segaQaSystems")?.split(',')?.toSet()
        val allGames = arguments.getString("segaQaAllGames") == "true"
        val targetFrames = arguments.getString("segaQaFrames")?.toIntOrNull()?.coerceIn(180, 1800) ?: 360
        val matrix = EmulationPlatform.entries.filter { it.isSega && (requestedSystems == null || it.name in requestedSystems) }.flatMap { platform ->
            val games = candidates.sortedBy { it.name }.filter {
                !it.name.contains("BIOS", true) && SegaPlatformDetector.detectFile(it) == platform
            }
            check(games.isNotEmpty()) { "No owner-supplied game for $platform" }
            (if (allGames) games else games.take(1)).map { platform to it }
        }
        // Provision only explicitly supplied regional dumps; never overwrite an installed BIOS.
        for ((region, pattern) in listOf(SegaCdBios.Region.US to "Sega-CD Model 2 BIOS V2.11 (U)",
            SegaCdBios.Region.EU to "Mega-CD Model 2 BIOS V2.00 (E)")) {
            if (SegaCdBios.isInstalled(context, region)) continue
            val bios = (candidates + File("/storage/emulated/0/DreamcastGames/Bios").listFiles().orEmpty())
                .firstOrNull { it.name.startsWith(pattern) && it.length() == SegaCdBios.SIZE.toLong() }
            bios?.let { assertTrue("Install supplied $region BIOS", SegaCdBios.install(context, Uri.fromFile(it), region)) }
        }
        val qa = File(context.cacheDir, "sega-owner-game-qa").apply { mkdirs() }
        val consumer = HandlerThread("SegaRetailImages").apply { start() }
        val previousSettings = CoreRuntime.settings.toMap()
        val preferences = AppPreferences(context)
        val previousVolume = runBlocking { preferences.audioVolume.first() }
        val previousMuted = runBlocking { preferences.audioMuted.first() }
        CoreRuntime.initialize(context)
        CoreRuntime.setDataRootOverride(qa.absolutePath)
        CoreRuntime.setAudioGain(0, true)
        try {
            for ((platform, game) in matrix) {
                CoreRuntime.setPerformanceMetricsEnabled(true, true)
                val frames = AtomicInteger()
                val latestImage = AtomicReference<Bitmap>()
                val sawVisiblePixels = java.util.concurrent.atomic.AtomicBoolean()
                ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 3).use { reader ->
                    reader.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.use { image ->
                        if (frames.incrementAndGet() % 30 == 0) {
                            val plane = image.planes[0]
                            val rgba = ByteArray(image.width * image.height * 4)
                            val buffer = plane.buffer.duplicate()
                            for (row in 0 until image.height) {
                                buffer.position(row * plane.rowStride)
                                buffer.get(rgba, row * image.width * 4, image.width * 4)
                            }
                            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).apply {
                                copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
                            }
                            // A stream of black buffers is not a successful rendered boot.
                            if (!sawVisiblePixels.get()) {
                                for (y in 0 until bitmap.height step 8) {
                                    for (x in 0 until bitmap.width step 8) {
                                        if ((bitmap.getPixel(x, y) and 0x00ffffff) != 0) sawVisiblePixels.set(true)
                                    }
                                }
                            }
                            latestImage.set(bitmap)
                        }
                    } }, Handler(consumer.looper))
                    instrumentation.runOnMainSync { CoreRuntime.attachSurface(reader.surface, 640, 480) }
                    try {
                        CoreRuntime.launchIdentityPath = game.absolutePath
                        CoreRuntime.launchCoreOptions = emptyMap()
                        CoreRuntime.setDisplayAspectRatio(1)
                        val booted = CoreRuntime.start(game.absolutePath, false)
                        val bootError = CoreRuntime.bridge.nativeLastBootError()
                        if (!booted && bootError?.startsWith("UNSUPPORTED_DS_COLLECTION_ROM:") == true) {
                            assertEquals(EmulationPlatform.GENESIS, platform)
                            Log.i("SegaRetailQA", "$platform file=${game.name} result=REJECTED_KNOWN_DS_COLLECTION_ROM detail=$bootError")
                            return@use
                        }
                        assertTrue("Boot $platform: ${game.name}; $bootError", booted)
                        assertEquals(platform, CoreRuntime.platform)
                        val started = System.nanoTime()
                        val deadline = started + TimeUnit.SECONDS.toNanos(60)
                        var nextStartFrame = 90
                        while (frames.get() < targetFrames && System.nanoTime() < deadline && CoreRuntime.failure.value == null) {
                            if (frames.get() >= nextStartFrame) {
                                CoreRuntime.setPadButtons(0, 0xffff and (1 shl 3).inv())
                                Thread.sleep(100)
                                CoreRuntime.setPadButtons(0, 0xffff)
                                nextStartFrame += 120
                            }
                            Thread.sleep(10)
                        }
                        assertNull("Runtime failure ${game.name}", CoreRuntime.failure.value)
                        assertTrue("Too few rendered frames $platform: ${frames.get()}", frames.get() >= 180)
                        assertTrue("Only black frames from ${game.name}", sawVisiblePixels.get())
                        val elapsed = (System.nanoTime() - started) / 1e9
                        val performance = CoreRuntime.performanceMetricsSnapshot()?.replace('\n', ' ')
                        CoreRuntime.pause()
                        latestImage.get()?.let { bitmap ->
                            File(qa, "${platform.name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            File(qa, "${platform.name}-${game.nameWithoutExtension.hashCode().toUInt().toString(16)}.png")
                                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }
                        val state = File(qa, "${platform.name}.rstate")
                        assertTrue("Save $platform", CoreRuntime.saveState(state.absolutePath))
                        assertTrue("Restore $platform", CoreRuntime.loadState(state.absolutePath))
                        Log.i("SegaRetailQA", "$platform file=${game.name} frames=${frames.get()} elapsed=$elapsed fps=${frames.get()/elapsed} stateBytes=${state.length()} metrics=$performance")
                    } finally {
                        CoreRuntime.shutdown()
                        instrumentation.runOnMainSync { CoreRuntime.detachSurface() }
                    }
                }
            }
        } finally {
            CoreRuntime.shutdown()
            CoreRuntime.setPerformanceMetricsEnabled(false, false)
            CoreRuntime.setDataRootOverride(null)
            CoreRuntime.launchIdentityPath = null
            CoreRuntime.launchCoreOptions = emptyMap()
            CoreRuntime.settings.clear()
            CoreRuntime.settings.putAll(previousSettings)
            CoreRuntime.setAudioGain(previousVolume, previousMuted)
            consumer.quitSafely()
            consumer.join()
        }
    }
}
