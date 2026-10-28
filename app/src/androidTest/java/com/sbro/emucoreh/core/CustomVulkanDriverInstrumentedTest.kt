package com.sbro.emucoreh.core

import android.graphics.PixelFormat
import android.content.ContextWrapper
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.util.concurrent.atomic.AtomicInteger

class CustomVulkanDriverInstrumentedTest {
    @Test fun liveCatalogDownloadsAndInstallsDriver() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val supplied = File(InstrumentationRegistry.getArguments().getString("driverQaArchive").orEmpty())
        assumeTrue("Network test is opt-in with the owner-supplied QA archive", supplied.isFile)
        val root = File(context.cacheDir, "live-driver-catalog-qa").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        try {
            val repository = GpuDriverCatalogRepository(isolated)
            val catalog = repository.loadCatalog()
            assertTrue("Catalog should include the tested Turnip driver", catalog.any { it.downloadUrl.contains("Turnip_Gen8_V35.zip") })
            val driver = catalog.first { it.downloadUrl.contains("Turnip_Gen8_V35.zip") }
            val archive = repository.downloadDriver(driver) { }
            val manager = GpuDriverManager(isolated)
            val name = manager.installFromArchive(archive, driver.vendor)
            assertTrue(manager.listInstalledDrivers().any { it.name == name && it.isUsable })
            assertEquals(null, manager.resolveUsableDriverPath(File(root, "missing.so").absolutePath))
        } finally { root.deleteRecursively() }
    }

    @Test fun runtimeLoadsSelectedDriverAndResetsToSystem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File(InstrumentationRegistry.getArguments().getString("driverQaArchive").orEmpty())
        assumeTrue("Supply a compatible catalog archive", archive.isFile && archive.canRead())
        val root = File(context.cacheDir, "runtime-driver-qa").apply { mkdirs() }
        val testArchive = File(root, "EmuCoreH-runtime-qa-${System.nanoTime()}.zip")
        archive.copyTo(testArchive)
        val manager = GpuDriverManager(context)
        val name = manager.installFromArchive(testArchive)
        val path = requireNotNull(manager.readMainLibraryPath(name))
        val rom = File(root, "test.sms").apply {
            writeBytes(ByteArray(32768).apply {
                byteArrayOf(0xf3.toByte(), 0x21, 0, 0xc0.toByte(), 0x34, 0x18, 0xfd.toByte()).copyInto(this)
            })
        }
        val consumer = HandlerThread("RuntimeDriverQaFrames").apply { start() }
        try {
            CoreRuntime.shutdown()
            CoreRuntime.initialize(context)
            CoreRuntime.updateSetting("EmuCoreH/Runtime", "Renderer", RendererDefaults.VULKAN.toString())
            ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 3).use { reader ->
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
                CoreRuntime.attachSurface(reader.surface, 320, 240)
                for ((selection, expected) in listOf(path to "custom", null to "system")) {
                    CoreRuntime.selectedGpuDriverPath = selection
                    assertTrue("Runtime failed to boot", CoreRuntime.start(rom.absolutePath, false))
                    val deadline = android.os.SystemClock.uptimeMillis() + 10000
                    while (CoreRuntime.bridge.vulkanDriverStatus() == "pending" && android.os.SystemClock.uptimeMillis() < deadline)
                        Thread.sleep(50)
                    assertEquals(expected, CoreRuntime.bridge.vulkanDriverStatus())
                    if (expected == "custom") assertTrue(CoreRuntime.bridge.vulkanDriverName().contains("Mesa", true))
                    CoreRuntime.shutdown()
                }
                CoreRuntime.detachSurface()
            }
        } finally {
            CoreRuntime.shutdown()
            CoreRuntime.selectedGpuDriverPath = null
            CoreRuntime.detachSurface()
            manager.remove(name)
            consumer.quitSafely()
            consumer.join(3000)
            root.deleteRecursively()
        }
    }

    @Test fun catalogDriverRunsOwnerDreamcastGame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val archive = File(args.getString("driverQaArchive").orEmpty())
        val game = File(args.getString("dreamcastQaPath").orEmpty())
        assumeTrue("Supply a compatible driver and Dreamcast disc", archive.isFile && archive.canRead() && game.isFile && game.canRead())
        val root = File(context.cacheDir, "custom-vulkan-dreamcast-qa").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir(): File = root }
        val manager = GpuDriverManager(isolated)
        val driverPath = requireNotNull(manager.readMainLibraryPath(manager.installFromArchive(archive)))
        val consumer = HandlerThread("VulkanDreamcastQaFrames").apply { start() }
        val frames = AtomicInteger()
        CoreRuntime.shutdown()
        val bridge = CoreRuntime.bridge
        try {
            ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 3).use { reader ->
                reader.setOnImageAvailableListener({ source ->
                    source.acquireLatestImage()?.use { frames.incrementAndGet() }
                }, Handler(consumer.looper))
                assertTrue(bridge.configureVulkanDriver(driverPath, context.applicationInfo.nativeLibraryDir, root.absolutePath))
                assertTrue(bridge.nativeSelectCore(0))
                bridge.nativeInit(root.absolutePath, root.absolutePath, root.absolutePath)
                bridge.nativeSetOption("reicast_hle_bios", "enabled")
                val session = bridge.createSession()
                try {
                    assertEquals(0, bridge.setSurface(session, reader.surface, RendererDefaults.CORE_VULKAN))
                    assertEquals("Dreamcast content: ${bridge.nativeLastBootError()}", 0, bridge.loadDisc(session, game.absolutePath))
                    assertTrue(bridge.ensureHardwareContext())
                    repeat(300) { bridge.runFrame(session) }
                    assertTrue("Dreamcast must produce display frames", frames.get() > 0)
                    assertEquals("custom", bridge.vulkanDriverStatus())
                    assertTrue("Actual Vulkan driver: ${bridge.vulkanDriverName()}",
                        bridge.vulkanDriverName().contains("Mesa", ignoreCase = true) ||
                            bridge.vulkanDriverName().contains("Turnip", ignoreCase = true))
                } finally { bridge.destroySession(session) }
            }
        } finally {
            bridge.configureVulkanDriver(null, context.applicationInfo.nativeLibraryDir, root.absolutePath)
            bridge.nativeSelectCore(0)
            CoreRuntime.initialize(context)
            consumer.quitSafely()
            consumer.join(3000)
            root.deleteRecursively()
        }
    }

    @Test fun catalogDriverLoadsAndRendersWithVulkan() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val archive = File(InstrumentationRegistry.getArguments().getString("driverQaArchive").orEmpty())
        assumeTrue("Supply a compatible owner-selected catalog archive", archive.isFile && archive.canRead())
        val root = File(context.cacheDir, "custom-vulkan-catalog-qa").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir(): File = root }
        val manager = GpuDriverManager(isolated)
        val driverName = manager.installFromArchive(archive)
        val driverPath = requireNotNull(manager.readMainLibraryPath(driverName))
        val rom = File(root, "red.sms").apply {
            writeBytes(ByteArray(32768).apply {
                val code = intArrayOf(0xf3, 0x3e, 0x04, 0xd3, 0xbf, 0x3e, 0x80, 0xd3, 0xbf,
                    0x3e, 0x40, 0xd3, 0xbf, 0x3e, 0x81, 0xd3, 0xbf,
                    0x3e, 0x00, 0xd3, 0xbf, 0x3e, 0xc0, 0xd3, 0xbf,
                    0x3e, 0x03, 0x06, 0x20, 0xd3, 0xbe, 0x10, 0xfc, 0x18, 0xfe)
                code.forEachIndexed { index, value -> this[index] = value.toByte() }
            })
        }
        val consumer = HandlerThread("VulkanCustomQaFrames").apply { start() }
        CoreRuntime.shutdown()
        val bridge = CoreRuntime.bridge
        try {
            ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 3).use { reader ->
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
                assertTrue(bridge.configureVulkanDriver(driverPath, context.applicationInfo.nativeLibraryDir, root.absolutePath))
                assertTrue(bridge.nativeSelectCore(1))
                bridge.nativeInit(root.absolutePath, root.absolutePath, root.absolutePath)
                val session = bridge.createSession()
                try {
                    assertEquals(0, bridge.setSurface(session, reader.surface, RendererDefaults.CORE_VULKAN))
                    assertEquals(0, bridge.loadDisc(session, rom.absolutePath))
                    assertTrue(bridge.ensureHardwareContext())
                    repeat(30) { bridge.runFrame(session) }
                    assertEquals("Driver must actually load, not silently use the system driver", "custom", bridge.vulkanDriverStatus())
                    assertTrue("Selected Mesa driver must identify itself: ${bridge.vulkanDriverName()}",
                        bridge.vulkanDriverName().contains("Mesa", ignoreCase = true) ||
                            bridge.vulkanDriverName().contains("Turnip", ignoreCase = true))
                } finally { bridge.destroySession(session) }
            }
        } finally {
            bridge.configureVulkanDriver(null, context.applicationInfo.nativeLibraryDir, root.absolutePath)
            bridge.nativeSelectCore(0)
            CoreRuntime.initialize(context)
            consumer.quitSafely()
            consumer.join(3000)
            root.deleteRecursively()
        }
    }

    @Test fun invalidDriverFallsBackAndNextSessionUsesSystemDriver() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "custom-vulkan-driver-qa").apply { mkdirs() }
        val rom = File(root, "counter.sms").apply {
            writeBytes(ByteArray(32768).apply {
                byteArrayOf(0xf3.toByte(), 0x21, 0, 0xc0.toByte(), 0x34, 0x18, 0xfd.toByte()).copyInto(this)
            })
        }
        val consumer = HandlerThread("VulkanDriverQaFrames").apply { start() }
        CoreRuntime.shutdown()
        val bridge = CoreRuntime.bridge
        try {
            ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 3).use { reader ->
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
                fun runSession(path: String?, expected: String) {
                    assertTrue(bridge.configureVulkanDriver(path, context.applicationInfo.nativeLibraryDir, root.absolutePath))
                    assertTrue(bridge.nativeSelectCore(1))
                    bridge.nativeInit(root.absolutePath, root.absolutePath, root.absolutePath)
                    val session = bridge.createSession()
                    try {
                        assertEquals(0, bridge.setSurface(session, reader.surface, RendererDefaults.CORE_VULKAN))
                        assertEquals(0, bridge.loadDisc(session, rom.absolutePath))
                        assertTrue(bridge.ensureHardwareContext())
                        repeat(10) { bridge.runFrame(session) }
                        assertEquals(expected, bridge.vulkanDriverStatus())
                        assertFalse("Do not switch drivers while a Vulkan instance is active",
                            bridge.configureVulkanDriver(null, context.applicationInfo.nativeLibraryDir, root.absolutePath))
                    } finally { bridge.destroySession(session) }
                }
                runSession(File(root, "missing.so").absolutePath, "fallback")
                runSession(null, "system")
            }
        } finally {
            bridge.configureVulkanDriver(null, context.applicationInfo.nativeLibraryDir, root.absolutePath)
            bridge.nativeSelectCore(0)
            CoreRuntime.initialize(context)
            consumer.quitSafely()
            consumer.join(3000)
            root.deleteRecursively()
        }
    }
}
