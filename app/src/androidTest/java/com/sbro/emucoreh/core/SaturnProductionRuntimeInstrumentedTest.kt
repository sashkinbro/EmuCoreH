package com.sbro.emucoreh.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucoreh.data.AppPreferences
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exercises the application's actual worker, storage, options and SAF launch paths. */
@RunWith(AndroidJUnit4::class)
class SaturnProductionRuntimeInstrumentedTest {
    @Test fun ownerDiscUsesProductionLifecycleOptionsAndSafWithoutUserStorageWrites() {
        val args = InstrumentationRegistry.getArguments()
        val game = args.getString("saturnQaGame")?.let(::File)
        val bios = args.getString("saturnQaBios")?.let(::File)
        assumeTrue("Supply an owner CHD and BIOS", game?.isFile == true && game.canRead() &&
            game.extension.equals("chd", true) && bios?.isFile == true && bios.canRead())
        requireNotNull(game)
        requireNotNull(bios)
        assumeTrue("Do not interrupt an active user session", !CoreRuntime.hasSession())

        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val token = "saturn-production-${UUID.randomUUID()}"
        val root = File(base.cacheDir, token).apply { mkdirs() }
        val shared = File(base.cacheDir, "shared/$token").apply { mkdirs() }
        val preferences = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
            override fun getExternalFilesDirs(type: String?) = arrayOf(getExternalFilesDir(type))
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scoped = "$token-$name"
                preferences += scoped
                return base.getSharedPreferences(scoped, mode)
            }
        }
        // This reads the normal DataStore once; the test never writes it or initializes
        // another DataStore instance against a wrapper context.
        val previousDataRoot = AppPreferences(base).getEmulatorDataPathSync()
        val previousSettings = HashMap(CoreRuntime.settings)
        val previousLaunchOptions = CoreRuntime.launchCoreOptions
        val previousIdentity = CoreRuntime.launchIdentityPath
        val previousNativeCore = CoreRuntime.platform.coreId
        val consumer = HandlerThread("Saturn-production-pixels").apply { start() }
        val presented = AtomicInteger()
        val readers = mutableListOf<ImageReader>()

        fun reader(): ImageReader = ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { presented.incrementAndGet() }
            }, Handler(consumer.looper))
            readers += this
        }

        fun awaitFrames(first: Int, count: Int = 3) {
            val deadline = SystemClock.uptimeMillis() + 30_000
            while (presented.get() < first + count && SystemClock.uptimeMillis() < deadline && CoreRuntime.failure.value == null)
                SystemClock.sleep(20)
            assertNull("Production worker failure", CoreRuntime.failure.value)
            assertTrue("Expected $count actual presented frames; got ${presented.get() - first}", presented.get() >= first + count)
            assertTrue(CoreRuntime.isRunning())
        }

        fun pauseAndRoundTrip(name: String) {
            CoreRuntime.pause()
            // Drain a frame already in flight plus the consumer's queued image callbacks.
            SystemClock.sleep(150)
            val state = File(root, "$name.rstate")
            assertTrue("Save on the production GL worker", CoreRuntime.saveState(state.path))
            val pausedState = state.readBytes()
            val payload = requireNotNull(CoreSaveState.unwrap(pausedState, 3)) { "Beetle Saturn envelope" }
            assertEquals("MDFNSVST", String(payload, 0, 8, Charsets.US_ASCII))
            val check = File(root, "$name-check.rstate")
            SystemClock.sleep(150)
            assertTrue(CoreRuntime.saveState(check.path))
            assertArrayEquals("Guest state remains frozen while paused", pausedState, check.readBytes())
            assertTrue("Load on the production GL worker", CoreRuntime.loadState(state.path))
            SystemClock.sleep(150)
            assertTrue(CoreRuntime.saveState(check.path))
            assertArrayEquals("Paused restore does not advance guest state", pausedState, check.readBytes())
            // A state from another Beetle Saturn core version must be rejected before
            // any guest state is replaced.
            val unsupported = pausedState.copyOf()
            val words = ByteBuffer.wrap(unsupported).order(ByteOrder.LITTLE_ENDIAN)
            val stateOffset = 12 // App core envelope, then the Mednafen header.
            assertEquals("MDFNSVST", String(unsupported, stateOffset, 8, Charsets.US_ASCII))
            words.putInt(stateOffset + 16, SaturnSaveFiles.STATE_VERSION xor 1)
            val unsupportedFile = File(root, "$name-unsupported.rstate").apply { writeBytes(unsupported) }
            assertFalse("Unsupported state must be rejected", CoreRuntime.loadState(unsupportedFile.path))
            assertTrue(CoreRuntime.saveState(check.path))
            assertArrayEquals("Rejected state must not mutate guest state", pausedState, check.readBytes())
        }

        try {
            CoreRuntime.shutdown()
            CoreRuntime.initialize(context)
            val isolatedData = EmulatorStorage.runtimeDirectories(context).root
            CoreRuntime.setDataRootOverride(isolatedData.absolutePath)
            CoreRuntime.settings.clear()
            CoreRuntime.launchIdentityPath = game.absolutePath
            CoreRuntime.setHostForeground(true)
            assertTrue("Validated user BIOS import", SaturnBios.installDetected(context, Uri.fromFile(bios), bios.length()))
            assertTrue(SaturnBios.isInstalled(context))

            SaturnCoreOptions.set(context, "beetle_saturn_midsync", "enabled")
            CoreRuntime.launchCoreOptions = mapOf("beetle_saturn_midsync" to "disabled")
            assertEquals("enabled", CoreRuntime.coreOptionValue("beetle_saturn_midsync"))
            var target = reader()
            CoreRuntime.attachSurface(target.surface, 640, 480)
            var first = presented.get()
            assertTrue("Production local launch: ${CoreRuntime.failure.value}", CoreRuntime.start(game.path, biosOnly = false))
            assertEquals(EmulationPlatform.SATURN, CoreRuntime.platform)
            awaitFrames(first)
            assertEquals(game.absolutePath, CoreRuntime.launchIdentityPath)
            assertEquals("enabled", SaturnCoreOptions.value(context, "beetle_saturn_midsync"))
            assertEquals("disabled", CoreRuntime.launchCoreOptions["beetle_saturn_midsync"])
            pauseAndRoundTrip("local")

            CoreRuntime.detachSurface()
            target = reader()
            CoreRuntime.attachSurface(target.surface, 640, 480)
            assertTrue(CoreRuntime.hasAttachedSurface(target.surface, 640, 480))
            first = presented.get()
            CoreRuntime.resume()
            awaitFrames(first)
            CoreRuntime.shutdown()

            // A subsequent boot without per-game overrides must retain the separate global profile.
            CoreRuntime.launchCoreOptions = emptyMap()
            first = presented.get()
            assertTrue(CoreRuntime.start(game.path, biosOnly = false))
            awaitFrames(first)
            assertEquals("enabled", CoreRuntime.coreOptionValue("beetle_saturn_midsync"))
            CoreRuntime.shutdown()

            assumeTrue("Space for one temporary SAF image", shared.usableSpace > game.length() + 64L * 1024 * 1024)
            val sharedGame = File(shared, game.name)
            game.copyTo(sharedGame)
            val uri = FileProvider.getUriForFile(base, "${base.packageName}.fileprovider", sharedGame)
            val prepared = File(JGenesisContentPreparer.prepare(context, uri.toString()))
            assertTrue("SAF media remains in isolated cache", prepared.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            assertEquals(game.length(), prepared.length())
            CoreRuntime.launchCoreOptions = mapOf("beetle_saturn_midsync" to "disabled")
            first = presented.get()
            assertTrue("Production SAF launch: ${CoreRuntime.failure.value}", CoreRuntime.start(uri.toString(), biosOnly = false))
            assertEquals(EmulationPlatform.SATURN, CoreRuntime.platform)
            awaitFrames(first)
            assertEquals("Original owner identity survives materialization", game.absolutePath, CoreRuntime.launchIdentityPath)
            pauseAndRoundTrip("saf")
            CoreRuntime.shutdown()
            assertFalse("Prepared SAF media released after unload", prepared.exists())

            SaturnCoreOptions.resetAll(context)
            CoreRuntime.launchCoreOptions = emptyMap()
            assertEquals("disabled", CoreRuntime.coreOptionValue("beetle_saturn_midsync"))
        } finally {
            CoreRuntime.shutdown()
            CoreRuntime.detachSurface()
            CoreRuntime.setHostForeground(false)
            CoreRuntime.settings.clear()
            CoreRuntime.settings.putAll(previousSettings)
            CoreRuntime.launchCoreOptions = previousLaunchOptions
            CoreRuntime.launchIdentityPath = previousIdentity
            CoreRuntime.setDataRootOverride(previousDataRoot)
            CoreRuntime.bridge.nativeSelectCore(previousNativeCore)
            CoreRuntime.initialize(base)
            val drained = CountDownLatch(1)
            Handler(consumer.looper).post {
                try {
                    readers.forEach { it.setOnImageAvailableListener(null, null); it.close() }
                } finally { drained.countDown() }
            }
            check(drained.await(5, TimeUnit.SECONDS)) { "Image consumer did not finish teardown" }
            consumer.quitSafely()
            consumer.join(3000)
            preferences.forEach(base::deleteSharedPreferences)
            shared.deleteRecursively()
            root.deleteRecursively()
        }
    }
}
