package com.sbro.emucoreh.core

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Self-authored ROM fixtures; no games or firmware are required. */
class SegaRuntimeInstrumentedTest {
    @Test fun presentsSegaFramesRestoresStatesAndSwitchesBackToFlycast() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "sega-runtime-qa").apply { mkdirs() }
        val consumer = HandlerThread("SegaQaFrames").apply { start() }
        val handler = Handler(consumer.looper)
        val previousSettings = CoreRuntime.settings.toMap()
        CoreRuntime.initialize(context)
        CoreRuntime.setDataRootOverride(directory.absolutePath)
        val has32xBios = Sega32XBios.Part.entries.all { Sega32XBios.isInstalled(context, it) }
        try {
            var previousState: File? = null
            for ((extension, platform) in listOf("md" to EmulationPlatform.GENESIS,
                "32x" to EmulationPlatform.SEGA_32X, "sms" to EmulationPlatform.MASTER_SYSTEM, "gg" to EmulationPlatform.GAME_GEAR,
                "sg" to EmulationPlatform.SG_1000)) {
                val rom = File(directory, "qa.$extension")
                rom.writeBytes(if (extension == "md" || extension == "32x") genesisRom() else byteArrayOf(0xf3.toByte(), 0x18, 0xfe.toByte()))
                if (extension == "32x" && !has32xBios) {
                    assertFalse("32X without imported BIOS must not boot", CoreRuntime.start(rom.absolutePath, false))
                    assertEquals(platform, CoreRuntime.platform)
                    assertTrue(CoreRuntime.bridge.nativeLastBootError().orEmpty()
                        .startsWith("SEGA_32X_BIOS_REQUIRED:"))
                    continue
                }
                val frames = AtomicInteger()
                ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 3).use { reader ->
                    reader.setOnImageAvailableListener({ source ->
                        source.acquireLatestImage()?.use { frames.incrementAndGet() }
                    }, handler)
                    instrumentation.runOnMainSync { CoreRuntime.attachSurface(reader.surface, 320, 240) }
                    try {
                        CoreRuntime.setDisplayAspectRatio(if (extension == "md") 3 else 1)
                        assertTrue("boot $extension", CoreRuntime.start(rom.absolutePath, false))
                        assertEquals(platform, CoreRuntime.platform)
                        assertTrue(CoreRuntime.bridge.getSystemInfo().contains("jgenesis", true))
                        assertFalse("cannot switch a live core", CoreRuntime.bridge.nativeSelectCore(0))
                        assertFalse("cartridge cannot change discs", CoreRuntime.hasDiscMedia())
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                        while (frames.get() < 5 && System.nanoTime() < deadline) Thread.sleep(10)
                        assertTrue("video for $extension, failure=${CoreRuntime.failure.value}", frames.get() >= 5)
                        if (extension == "md") {
                            val rect = CoreRuntime.bridge.getPresentRect()!!
                            assertEquals("per-game aspect survives Sega boot", 16f / 9f,
                                (rect[2] - rect[0]) / (rect[3] - rect[1]), 0.02f)
                        }
                        CoreRuntime.pause()
                        val state = File(directory, "$extension.rstate")
                        assertTrue("save $extension", CoreRuntime.saveState(state.absolutePath))
                        assertTrue("load $extension", CoreRuntime.loadState(state.absolutePath))
                        previousState?.let { assertFalse("reject other platform state", CoreRuntime.loadState(it.absolutePath)) }
                        previousState = state
                    } finally {
                        CoreRuntime.shutdown()
                        instrumentation.runOnMainSync { CoreRuntime.detachSurface() }
                    }
                }
            }
            assertTrue(CoreRuntime.bridge.nativeSelectCore(0))
            assertEquals(1, CoreRuntime.bridge.apiVersion())
            assertTrue(CoreRuntime.bridge.getSystemInfo().contains("Flycast", true))
        } finally {
            CoreRuntime.shutdown()
            CoreRuntime.setDataRootOverride(null)
            CoreRuntime.initialize(context)
            CoreRuntime.settings.clear()
            CoreRuntime.settings.putAll(previousSettings)
            consumer.quitSafely()
            consumer.join()
        }
    }

    @Test fun segaCdBiosDiscSwapAndStatesUseTheSameNativeFrontend() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "sega-cd-native-qa").apply { mkdirs() }
        val system = File(root, "system").apply { mkdirs() }
        val save = File(root, "save").apply { mkdirs() }
        val cue = File(root, "disc.cue").apply {
            writeText("FILE \"disc.bin\" BINARY\n TRACK 01 MODE1/2048\n INDEX 01 00:00:00\n")
        }
        File(root, "disc.bin").writeBytes(ByteArray(2048 * 16).apply {
            "SEGADISCSYSTEM".toByteArray().copyInto(this)
        })
        val bios = File(system, "bios_CD_U.bin")
        bios.delete() // This directory contains only this test's own synthetic fixture.
        CoreRuntime.shutdown()
        val bridge = CoreRuntime.bridge
        assertTrue(bridge.nativeSelectCore(1))
        bridge.nativeInit(system.absolutePath, save.absolutePath, root.absolutePath)
        bridge.nativeSetOption("jgenesis_region", "auto")
        bridge.resetAudioQueue()
        val handle = bridge.createSession()
        assertTrue(handle != 0L)
        try {
            assertEquals(1, bridge.probeSegaDisc(cue.absolutePath))
            assertTrue(bridge.loadDisc(handle, cue.absolutePath) != 0)
            assertTrue(bridge.nativeLastBootError().orEmpty().contains("BIOS"))
            bios.writeBytes(genesisRom().copyOf(SegaCdBios.SIZE))
            assertEquals(0, bridge.loadDisc(handle, cue.absolutePath))
            assertTrue(bridge.hasDiscMedia(handle))
            repeat(60) { bridge.runFrame(handle) }
            val state = File(root, "cd.rawstate")
            assertEquals(0, bridge.saveState(handle, state.absolutePath))
            assertEquals(0, bridge.loadState(handle, state.absolutePath))
            assertEquals(0, bridge.changeDisc(handle, cue.absolutePath))
            bridge.runFrame(handle)
        } finally {
            bridge.destroySession(handle)
            assertTrue(bridge.nativeSelectCore(0))
            CoreRuntime.initialize(context)
        }
    }

    @Test fun missing32xBiosFailsWithActionableNativeError() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "sega-32x-missing-qa").apply { mkdirs() }
        val system = File(context.filesDir, "jgenesis/system").apply { mkdirs() }
        val save = File(root, "save").apply { mkdirs() }
        val backups = Sega32XBios.Part.entries.associateWith { part ->
            File(system, part.fileName).takeIf { it.isFile }?.readBytes()
        }
        CoreRuntime.shutdown()
        try {
            backups.keys.forEach { File(system, it.fileName).delete() }
            val bridge = CoreRuntime.bridge
            assertTrue(bridge.nativeSelectCore(1))
            bridge.nativeInit(system.absolutePath, save.absolutePath, root.absolutePath)
            bridge.resetAudioQueue()
            val rom = File(root, "missing-bios.32x").apply { writeBytes(genesisRom()) }
            val handle = bridge.createSession()
            assertTrue(handle != 0L)
            try {
                assertNotEquals(0, bridge.loadDisc(handle, rom.absolutePath))
                assertTrue(bridge.nativeLastBootError().orEmpty()
                    .startsWith("SEGA_32X_BIOS_REQUIRED:"))
            } finally {
                bridge.destroySession(handle)
                assertTrue(bridge.nativeSelectCore(0))
            }
        } finally {
            backups.forEach { (part, bytes) ->
                val target = File(system, part.fileName)
                if (bytes != null) target.writeBytes(bytes) else target.delete()
            }
            CoreRuntime.initialize(context)
        }
    }

    private fun genesisRom(): ByteArray = ByteArray(32768).apply {
        byteArrayOf(0x00, 0xff.toByte(), 0xff.toByte(), 0xf0.toByte(), 0, 0, 2, 0).copyInto(this)
        "SEGA MEGA DRIVE ".toByteArray().copyInto(this, 0x100)
        "EMUCORE QA".toByteArray().copyInto(this, 0x150)
        this[0x1f0] = 'U'.code.toByte()
        byteArrayOf(0x4e, 0x72, 0x27, 0x00, 0x60, 0xfa.toByte()).copyInto(this, 0x200)
    }
}
