// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucoreh.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Opt in with model3SaveWorkflow=true; retail data is supplied by the device owner only. */
class Model3RelocatedSaveWorkflowInstrumentedTest {
    @Test fun relocatedOwnerVf3SlotListsAndLoadsThroughTheRealFrontend() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with model3SaveWorkflow=true", args.getString("model3SaveWorkflow") == "true")
        val app = instrumentation.targetContext
        val ownerRoot = args.getString("model3QaRoot") ?: File(app.getExternalFilesDir(null), "qa-model3-romset").path
        val owner = File(args.getString("model3SaveQaPath") ?: File(ownerRoot, "vf3.zip").path)
        assumeTrue("Provide readable owner vf3.zip", owner.isFile && owner.canRead())
        assertFalse("Stop an active game before isolated QA", CoreRuntime.hasSession())
        val id = "model3-save-workflow-${UUID.randomUUID()}"
        val root = File(app.cacheDir, "shared/$id").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$id-$name", mode)
        }
        val previousSettings = CoreRuntime.settings.toMap()
        val previousOptions = CoreRuntime.launchCoreOptions
        val previousIdentity = CoreRuntime.launchIdentityPath
        val runtime = File(root, "runtime").apply { mkdirs() }
        val consumer = HandlerThread("Model3RelocatedSaveFrames").apply { start() }
        val handler = Handler(consumer.looper)
        try {
            val first = File(root, "original/vf3.zip").apply { parentFile!!.mkdirs(); owner.copyTo(this) }
            val relocated = File(root, "relocated/vf3.zip").apply { parentFile!!.mkdirs(); owner.copyTo(this) }
            val expectedSet = requireNotNull(Model3ContentPreparer.resolveExpectedSet(context, first.path))
            EmulatorBridge.initializeOnce(context)
            NativeApp.reloadDataRoot(runtime.path)
            CoreRuntime.setDataRootOverride(runtime.path)
            CoreRuntime.launchCoreOptions = emptyMap()
            CoreRuntime.setHostForeground(true)
            val repository = SaveStateRepository(context, File(runtime, "sstates"))
            lateinit var savedBytes: ByteArray
            lateinit var savedFile: File
            runGame(first, handler) {
                assertEquals(expectedSet, NativeApp.loadedModel3SetForPath(first.path))
                assertTrue("Create real Model 3 checkpoint", NativeApp.saveStateToSlot(1))
                savedFile = File(requireNotNull(NativeApp.getSaveStatePathForFile(first.path, 1)))
                savedBytes = savedFile.readBytes()
                assertEquals(expectedSet, savedFile.inputStream().use(::readSaveStateImportHeader)?.model3Set)
            }
            assertNull("Unloaded identity must not leak", NativeApp.loadedModel3SetForPath(first.path))
            runGame(relocated, handler) {
                val absentCurrentHash = File(requireNotNull(NativeApp.getSaveStatePathForFile(relocated.path, 1)))
                assertFalse("Relocated path has no exact hash checkpoint", absentCurrentHash.exists())
                assertNull("Old library path is not this active launch", NativeApp.loadedModel3SetForPath(first.path))
                val shown = repository.listSlots(relocated.path, "M3-${expectedSet.uppercase(java.util.Locale.ROOT)}").single { it.slot == 1 }
                assertTrue("Repository exposes relocated checkpoint", shown.exists)
                assertEquals(savedFile.path, shown.absolutePath)
                assertTrue("Actual slot loader must use the same fallback file", NativeApp.loadStateFromSlot(1))
                val roundTrip = File(root, "roundtrip.rstate")
                assertTrue(CoreRuntime.saveState(roundTrip.path))
                assertArrayEquals("Paused loaded state is the relocated checkpoint", savedBytes, roundTrip.readBytes())
                assertFalse("Fallback loading does not rename or duplicate the checkpoint", absentCurrentHash.exists())
            }
        } finally {
            EmulatorBridge.shutdown()
            NativeApp.setSaveStateIdentityPath(null)
            CoreRuntime.setDataRootOverride(null)
            CoreRuntime.launchIdentityPath = previousIdentity
            CoreRuntime.launchCoreOptions = previousOptions
            EmulatorBridge.initializeOnce(app)
            CoreRuntime.settings.clear()
            CoreRuntime.settings.putAll(previousSettings)
            consumer.quitSafely()
            consumer.join()
            root.deleteRecursively()
        }
    }

    private suspend fun runGame(rom: File, handler: Handler, block: suspend () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val frames = AtomicInteger()
        ImageReader.newInstance(496, 384, PixelFormat.RGBA_8888, 3).use { reader ->
            reader.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.use { frames.incrementAndGet() } }, handler)
            instrumentation.runOnMainSync { CoreRuntime.attachSurface(reader.surface, 496, 384) }
            try {
                assertTrue("Boot real frontend: ${CoreRuntime.failure.value}", EmulatorBridge.startEmulation(rom.path))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                while (frames.get() < 3 && System.nanoTime() < deadline && CoreRuntime.failure.value == null) Thread.sleep(10)
                assertTrue("Expected rendered frames: ${CoreRuntime.failure.value}", frames.get() >= 3)
                EmulatorBridge.pause()
                block()
            } finally {
                EmulatorBridge.shutdown()
                instrumentation.runOnMainSync { CoreRuntime.detachSurface() }
            }
        }
    }
}
