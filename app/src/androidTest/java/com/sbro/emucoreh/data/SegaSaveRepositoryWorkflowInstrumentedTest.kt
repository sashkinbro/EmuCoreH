package com.sbro.emucoreh.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucoreh.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the NativeApp slot API used by quicksave, repository listing and cold reload.
 * Every ROM/firmware byte is self-authored; all writes stay in one temporary QA directory.
 */
class SegaSaveRepositoryWorkflowInstrumentedTest {
    @Test fun allSixSystemsSaveListResolveAndReloadAcrossCoreSwitchAndFreshRepository() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        assertFalse("Stop an active game before running the isolated save workflow", CoreRuntime.hasSession())
        val id = "sega-save-workflow-${UUID.randomUUID()}"
        val root = File(app.cacheDir, "shared/$id").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$id-$name", mode)
        }
        val runtime = File(root, "runtime").apply { mkdirs() }
        val states = File(runtime, "sstates").apply { mkdirs() }
        val previousSettings = CoreRuntime.settings.toMap()
        val previousOptions = CoreRuntime.launchCoreOptions
        val previousIdentity = CoreRuntime.launchIdentityPath
        val consumer = HandlerThread("SegaSaveWorkflowFrames").apply { start() }
        val handler = Handler(consumer.looper)
        try {
            NativeApp.initializeOnce(app)
            NativeApp.reloadDataRoot(runtime.path)
            CoreRuntime.initialize(context)
            CoreRuntime.setDataRootOverride(runtime.path)
            CoreRuntime.launchCoreOptions = emptyMap()
            val bios = File(SegaCdBios.directory(context), "bios_CD_U.bin")
            bios.parentFile!!.mkdirs()
            bios.writeBytes(genesisRom().copyOf(SegaCdBios.SIZE))
            val games = EmulationPlatform.entries.filter { it.isSega }.map { platform ->
                val source = when (platform) {
                    EmulationPlatform.SEGA_CD -> File(root, "source.cue").apply {
                        File(root, "source.bin").writeBytes(ByteArray(2048 * 16).apply {
                            "SEGADISCSYSTEM".toByteArray().copyInto(this)
                        })
                        writeText("FILE \"source.bin\" BINARY\n TRACK 01 MODE1/2048\n INDEX 01 00:00:00\n")
                    }
                    else -> File(root, "source.${when (platform) {
                        EmulationPlatform.GENESIS -> "md"; EmulationPlatform.SEGA_32X -> "32x"
                        EmulationPlatform.MASTER_SYSTEM -> "sms"; EmulationPlatform.GAME_GEAR -> "gg"
                        else -> "sg"
                    }}").apply {
                        writeBytes(if (platform in setOf(EmulationPlatform.GENESIS, EmulationPlatform.SEGA_32X)) genesisRom()
                            else byteArrayOf(0xf3.toByte(), 0x18, 0xfe.toByte()))
                    }
                }
                val identity = if (platform == EmulationPlatform.GENESIS)
                    FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", source).toString() else source.path
                GameItem("QA ${platform.name}", identity, source.name, source.length(), source.lastModified(),
                    serial = "QA-${platform.name}", platform = platform.name)
            }
            val repository = SaveStateRepository(context, states)
            for (game in games) {
                runGame(context, game, handler) {
                    assertEquals(EmulationPlatform.fromName(game.platform), CoreRuntime.platform)
                    assertTrue("manual save ${game.platform}", NativeApp.saveStateToSlot(10))
                    assertTrue("auto save ${game.platform}", NativeApp.saveStateToSlot(0))
                    for (slot in listOf(0, 10)) {
                        val state = File(requireNotNull(NativeApp.getSaveStatePathForFile(game.path, slot)))
                        assertTrue("source identity file ${game.platform}", state.isFile)
                        assertEquals(EmulationPlatform.fromName(game.platform), state.inputStream().use(::readSaveStateImportHeader)?.platform)
                        assertEquals(state.path, repository.listSlots(game.path, game.serial).single { it.slot == slot }.absolutePath)
                    }
                    assertEquals(setOf(0, 10), repository.listEntries(game.path, game.title, game.serial).map { it.slot }.toSet())
                }
            }
            assertEquals("All systems retain independent slot namespaces", 12, repository.listEntries().size)
            assertTrue(CoreRuntime.bridge.nativeSelectCore(0))
            val coldRepository = SaveStateRepository(context, states)
            val coldEntries = coldRepository.enrichGlobalEntries(coldRepository.listEntries(), games)
            assertEquals(12, coldEntries.size)
            assertTrue(coldEntries.all { it.canLoad })
            games.forEach { game ->
                assertEquals(setOf(0, 10), coldEntries.filter { it.gamePath == game.path }.map { it.slot }.toSet())
            }
            for (game in games.reversed()) {
                CoreRuntime.initialize(context) // Fresh native/frontend initialization after another console ran.
                runGame(context, game, handler) {
                    assertTrue("reload manual ${game.platform}", NativeApp.loadStateFromSlot(10))
                    assertTrue("reload auto ${game.platform}", NativeApp.loadStateFromSlot(0))
                }
            }
        } finally {
            CoreRuntime.shutdown()
            NativeApp.setSaveStateIdentityPath(null)
            CoreRuntime.setDataRootOverride(null)
            CoreRuntime.launchIdentityPath = previousIdentity
            CoreRuntime.launchCoreOptions = previousOptions
            NativeApp.initializeOnce(app)
            CoreRuntime.settings.clear()
            CoreRuntime.settings.putAll(previousSettings)
            consumer.quitSafely()
            consumer.join()
            root.deleteRecursively()
        }
    }

    private fun runGame(context: Context, game: GameItem, handler: Handler, block: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prepared = JGenesisContentPreparer.prepare(context, game.path)
        NativeApp.setSaveStateIdentityPath(game.path)
        val frames = AtomicInteger()
        ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 3).use { reader ->
            reader.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.use { frames.incrementAndGet() } }, handler)
            instrumentation.runOnMainSync { CoreRuntime.attachSurface(reader.surface, 320, 240) }
            try {
                assertTrue("boot ${game.platform}", NativeApp.runVMThread(prepared))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (frames.get() < 3 && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue("frames ${game.platform}: ${CoreRuntime.failure.value}", frames.get() >= 3)
                CoreRuntime.pause()
                if (prepared != game.path) assertNotEquals(NativeApp.getSaveStatePathForFile(prepared, 10), NativeApp.getCurrentSaveStatePath(10))
                block()
            } finally {
                CoreRuntime.shutdown()
                instrumentation.runOnMainSync { CoreRuntime.detachSurface() }
            }
        }
    }

    private fun genesisRom() = ByteArray(32768).apply {
        byteArrayOf(0x00, 0xff.toByte(), 0xff.toByte(), 0xf0.toByte(), 0, 0, 2, 0).copyInto(this)
        "SEGA MEGA DRIVE ".toByteArray().copyInto(this, 0x100)
        "EMUCORE SAVE QA".toByteArray().copyInto(this, 0x150)
        this[0x1f0] = 'U'.code.toByte()
        byteArrayOf(0x4e, 0x72, 0x27, 0x00, 0x60, 0xfa.toByte()).copyInto(this, 0x200)
    }
}
