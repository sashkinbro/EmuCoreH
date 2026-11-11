package com.sbro.emucoreh.core

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in interactive QA of the production worker. Commands and captures stay in a disposable root.
 * A capture must be inspected for active gameplay before its timing is called gameplay performance.
 * Commands: game(path), run(ms,mask), capture(name), renderer(value), roundtrip, quit.
 * Masks use the app button layout, not libretro IDs: start=8, right=32,
 * up=16, down=64, left=128, square/A=32768, cross/B=16384, circle/C=8192.
 * Normal app input uses their active-low complement.
 */
class ReleaseGameplayInstrumentedTest {
    @Test fun ownerGamesUseProductionLifecycleAndRendererSwitches() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("releaseQaSession")
        assumeTrue("Explicit releaseQaSession opts into owner-content testing", token != null)
        requireNotNull(token)
        require(token.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        val base = instrumentation.targetContext
        val root = File(base.getExternalFilesDir(null), "release-audit/$token").apply { mkdirs() }
        val commands = File(root, "commands.jsonl")
        val events = File(root, "events.jsonl")
        val prefs = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "private/files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "private/cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private/no-backup").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?) = File(root, "private/external").apply { mkdirs() }
            override fun getExternalFilesDirs(type: String?) = arrayOf(getExternalFilesDir(type))
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scoped = "release-qa-$token-$name"
                prefs += scoped
                return base.getSharedPreferences(scoped, mode)
            }
        }
        val oldSettings = CoreRuntime.settings.toMap()
        val oldOptions = CoreRuntime.launchCoreOptions
        val oldIdentity = CoreRuntime.launchIdentityPath
        val image = AtomicReference<Bitmap>()
        val capturePending = AtomicBoolean(false)
        val imageProof = AtomicReference(IntArray(3))
        val consumer = HandlerThread("ReleaseGameplayCapture").apply { start() }
        val reader = ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 3)
        fun event(value: JSONObject) { events.appendText(value.toString() + "\n") }
        try {
            CoreRuntime.shutdown()
            CoreRuntime.initialize(context)
            CoreRuntime.settings.clear()
            CoreRuntime.launchCoreOptions = emptyMap()
            CoreRuntime.setDataRootOverride(File(root, "runtime-data").absolutePath)
            CoreRuntime.setHostForeground(true)
            CoreRuntime.setPerformanceMetricsEnabled(true, true)
            val biosPath = args.getString("releaseQaBios") ?: base.contentResolver.persistedUriPermissions
                .firstOrNull { it.uri.toString().contains("bios", ignoreCase = true) }?.uri?.toString()
            requireNotNull(biosPath) { "Select the owner BIOS folder in the app first" }
            val imported = BiosImport.installSelection(context, Uri.parse(biosPath))
            assertTrue("Owner firmware folder contains valid BIOS", imported.installed)
            CoreRuntime.setCoreOption("reicast_per_content_vmus", "All VMUs")
            CoreRuntime.setCoreOption("reicast_linked_vmu_storage", "disabled")
            CoreRuntime.setCoreOption(FlycastCoreOptions.HLE_BIOS_KEY, "enabled")
            assertEquals("Legacy preferences cannot enable HLE", "disabled",
                CoreRuntime.coreOptionValue(FlycastCoreOptions.HLE_BIOS_KEY))
            CoreRuntime.updateSetting("EmuCoreH", "UpscaleMultiplier", "1.0")
            CoreRuntime.updateSetting("EmuCoreH", "Renderer", RendererDefaults.OPENGL.toString())
            reader.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { frame ->
                    if (!capturePending.get()) return@use
                    val plane = frame.planes[0]
                    val proof = IntArray(3)
                    for (y in 0 until frame.height step 16) for (x in 0 until frame.width step 16) {
                        val offset = y * plane.rowStride + x * plane.pixelStride
                        proof[0]++
                        if ((plane.buffer.get(offset + 3).toInt() and 255) == 255) proof[1]++
                        if ((0..2).any { plane.buffer.get(offset + it).toInt() != 0 }) proof[2]++
                    }
                    imageProof.set(proof)
                    val bitmap = Bitmap.createBitmap(plane.rowStride / plane.pixelStride,
                        frame.height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(plane.buffer)
                    image.set(Bitmap.createBitmap(bitmap, 0, 0, frame.width, frame.height))
                    capturePending.set(false)
                }
            }, Handler(consumer.looper))
            CoreRuntime.attachSurface(reader.surface, 640, 480)
            event(JSONObject().put("ready", true).put("bios", imported.toString()))
            var processed = 0
            val deadline = SystemClock.uptimeMillis() + 30 * 60_000
            while (SystemClock.uptimeMillis() < deadline) {
                val lines = if (commands.isFile) commands.readLines().filter(String::isNotBlank) else emptyList()
                if (processed >= lines.size) { SystemClock.sleep(100); continue }
                val command = JSONObject(lines[processed])
                val output = JSONObject().put("id", processed).put("command", command)
                when (command.getString("op")) {
                    "game" -> {
                        CoreRuntime.shutdown()
                        CoreRuntime.launchIdentityPath = command.getString("path")
                        assertTrue("Launch ${command.getString("path")}: ${CoreRuntime.failure.value}",
                            CoreRuntime.start(command.getString("path"), false))
                        CoreRuntime.pause()
                        output.put("platform", CoreRuntime.platform.name)
                    }
                    "run" -> {
                        val duration = command.getLong("ms").also { require(it in 1..30000) }
                        CoreRuntime.setPadButtons(0, 0xffff xor command.optInt("mask", 0))
                        val before = requireNotNull(CoreRuntime.bridge.audioOutputStats(0))
                        val started = SystemClock.elapsedRealtime()
                        val cpuStarted = android.os.Process.getElapsedCpuTime()
                        CoreRuntime.resume()
                        SystemClock.sleep(duration)
                        CoreRuntime.pause()
                        CoreRuntime.setPadButtons(0, 0xffff)
                        val after = requireNotNull(CoreRuntime.bridge.audioOutputStats(0))
                        output.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                            .put("videoFrames", after[9] - before[9]).put("audioFrames", after[5] - before[5])
                            .put("cpuMs", android.os.Process.getElapsedCpuTime() - cpuStarted)
                            .put("metrics", CoreRuntime.performanceMetricsSnapshot())
                        assertNull("Worker failure", CoreRuntime.failure.value)
                        // A short button press can fall wholly within a legitimate
                        // disc-loading interval that repeats the previous image.
                        if (duration >= 5000) assertTrue("Game produces frames", after[9] > before[9])
                    }
                    "capture" -> {
                        val name = command.getString("name").also { require(it.matches(Regex("[a-zA-Z0-9_-]+"))) }
                        capturePending.set(true)
                        CoreRuntime.resume()
                        val captureDeadline = SystemClock.uptimeMillis() + 10000
                        while (capturePending.get() && SystemClock.uptimeMillis() < captureDeadline) SystemClock.sleep(10)
                        CoreRuntime.pause()
                        assertFalse("Capture timeout", capturePending.get())
                        val bitmap = requireNotNull(image.get()) { "No frame captured" }
                        File(root, "$name.png").outputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                        val proof = imageProof.get()
                        output.put("capture", "$name.png").put("samples", proof[0])
                            .put("opaqueSamples", proof[1]).put("visibleSamples", proof[2])
                        if (CoreRuntime.platform.isSaturn || CoreRuntime.platform.isSega) {
                            assertEquals("Software XRGB frames must be opaque on Android", proof[0], proof[1])
                        }
                    }
                    "renderer" -> assertTrue("Renderer restart", CoreRuntime.restartWithRenderer(command.getInt("value")))
                    "save", "load" -> {
                        val name = command.getString("name").also { require(it.matches(Regex("[a-zA-Z0-9_-]+"))) }
                        val state = File(root, "$name.rstate")
                        assertTrue(if (command.getString("op") == "save") CoreRuntime.saveState(state.path)
                            else CoreRuntime.loadState(state.path))
                    }
                    "roundtrip" -> {
                        val state = File(root, "roundtrip.rstate")
                        val check = File(root, "roundtrip-check.rstate")
                        assertTrue(CoreRuntime.saveState(state.path))
                        CoreRuntime.resume()
                        SystemClock.sleep(200)
                        CoreRuntime.pause()
                        assertTrue(CoreRuntime.loadState(state.path))
                        assertTrue(CoreRuntime.saveState(check.path))
                        assertArrayEquals("Paused game state round trip", state.readBytes(), check.readBytes())
                    }
                    "quit" -> { event(output.put("ok", true)); return }
                    else -> error("Unknown QA command")
                }
                event(output.put("ok", true))
                processed++
            }
            error("QA command deadline exceeded")
        } catch (failure: Throwable) {
            event(JSONObject().put("failure", failure.stackTraceToString()))
            throw failure
        } finally {
            CoreRuntime.shutdown()
            CoreRuntime.detachSurface()
            CoreRuntime.setHostForeground(false)
            CoreRuntime.setPerformanceMetricsEnabled(false, false)
            CoreRuntime.settings.clear()
            CoreRuntime.settings.putAll(oldSettings)
            CoreRuntime.launchCoreOptions = oldOptions
            CoreRuntime.launchIdentityPath = oldIdentity
            CoreRuntime.setDataRootOverride(null)
            CoreRuntime.bridge.nativeSelectCore(0)
            CoreRuntime.initialize(base)
            reader.setOnImageAvailableListener(null, null)
            consumer.quitSafely()
            consumer.join(3000)
            reader.close()
            prefs.forEach(base::deleteSharedPreferences)
        }
    }
}
