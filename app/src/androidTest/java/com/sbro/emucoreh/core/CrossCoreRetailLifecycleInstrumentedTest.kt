package com.sbro.emucoreh.core

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Actual owner games, in one process; saves and firmware copies are isolated from normal play. */
class CrossCoreRetailLifecycleInstrumentedTest {
    @Test fun saturnGenesisDreamcastModel3ThenSaturnPresentAndRestoreInOneProcess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val saturn = File(args.getString("saturnQaGame") ?: "/sdcard/Sega/Saturn/Panzer Dragoon (USA) (5S).chd")
        val bios = File(args.getString("saturnQaBios") ?: "/sdcard/Sega/Saturn/Sega Saturn BIOS (E) (1.00).bin")
        val genesis = File(args.getString("genesisQaGame") ?: "/sdcard/Sega/Golden Axe (World).md")
        val dreamcast = File(args.getString("dreamcastQaPath") ?: "/sdcard/DreamcastGames/Games/Crazy Taxi (USA).cue")
        val model3 = File(args.getString("model3QaGame") ?: "/sdcard/Sega/Model3/srally2.zip")
        assumeTrue("All four owner game files and Saturn firmware must be available",
            listOf(saturn, bios, genesis, dreamcast, model3).all { it.isFile && it.canRead() })
        val root = File(context.getExternalFilesDir(null), "qa-cross-core-${System.nanoTime()}").apply { check(mkdirs()) }
        val bridge = CoreRuntime.bridge
        val consumer = HandlerThread("CrossCoreRetailImages").apply { start() }
        CoreRuntime.shutdown()
        try {
            for ((index, entry) in listOf(3 to saturn, 1 to genesis, 0 to dreamcast, 2 to model3, 3 to saturn).withIndex()) {
                val (core, game) = entry
                val sessionRoot = File(root, "$index-core-$core").apply { mkdirs() }
                val system = File(sessionRoot, "system").apply { mkdirs() }
                val saves = File(sessionRoot, "save").apply { mkdirs() }
                if (core == 3) bios.copyTo(File(system, "saturn_bios.bin"))
                if (core == 2) {
                    val definitions = File(system, "supermodel/Games.xml").apply { parentFile!!.mkdirs() }
                    context.assets.open("supermodel/Games.xml").use { source -> definitions.outputStream().use(source::copyTo) }
                }
                val started = System.nanoTime()
                assertTrue("Select core $core after step ${index - 1}", bridge.nativeSelectCore(core))
                bridge.nativeInit(system.path, saves.path, sessionRoot.path)
                bridge.nativeSetShaderPreset("", false)
                bridge.nativeSetShaderEffect(0)
                bridge.setFrameSkip(0)
                bridge.setTimeControl(0)
                bridge.setDisplayCrop(0, 0, 0, 0)
                bridge.resetAudioQueue()
                if (core == 0) {
                    bridge.nativeSetOption("reicast_hle_bios", "enabled")
                    bridge.nativeSetOption("reicast_per_content_vmus", "All VMUs")
                    bridge.nativeSetOption("reicast_linked_vmu_storage", "disabled")
                    bridge.nativeSetOption("reicast_internal_resolution", "640x480")
                }
                if (core == 3) bridge.nativeSetOption("yabasanshiro_frameskip", "disabled")
                val presented = AtomicInteger()
                ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 3).use { reader ->
                    reader.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.use { presented.incrementAndGet() } }, Handler(consumer.looper))
                    val output = NativeAudioOutput()
                    val handle = bridge.createSession()
                    try {
                        assertNotEquals(0L, handle)
                        output.play()
                        assertEquals(0, bridge.setSurface(handle, reader.surface, RendererDefaults.CORE_OPENGL))
                        assertEquals("Core $core load ${game.name}: ${bridge.nativeLastBootError()}", 0, bridge.loadDisc(handle, game.path))
                        assertTrue("Core $core GPU", bridge.ensureHardwareContext())
                        val bootMs = (System.nanoTime() - started) / 1_000_000
                        assertFalse("Running session owns core $core", bridge.nativeSelectCore(if (core == 1) 3 else 1))
                        repeat(600) { frame ->
                            val press = if (frame % 120 in 60..61) 1 shl 3 else 0
                            bridge.setPadButtons(handle, 0, 0xffff xor press)
                            bridge.runFrame(handle)
                        }
                        assertTrue("Core $core presents after prior engines", presented.get() > 0)
                        val state = File(sessionRoot, "roundtrip.state")
                        assertEquals("Core $core serialize", 0, bridge.saveState(handle, state.path))
                        repeat(12) { bridge.runFrame(handle) }
                        assertEquals("Core $core restore", 0, bridge.loadState(handle, state.path))
                        repeat(12) { bridge.runFrame(handle) }
                        Log.i("CrossCoreRetailQA", "step=$index core=$core game=${game.name} bootMs=$bootMs frames=${presented.get()} stateBytes=${state.length()}")
                    } finally {
                        bridge.destroySession(handle)
                        output.release()
                    }
                }
            }
        } finally {
            bridge.nativeSelectCore(0)
            CoreRuntime.initialize(context)
            consumer.quitSafely()
            consumer.join()
        }
    }
}
