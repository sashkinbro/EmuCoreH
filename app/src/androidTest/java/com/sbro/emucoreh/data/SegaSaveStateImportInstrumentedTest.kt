package com.sbro.emucoreh.data

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucoreh.core.CoreSaveState
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated storage and synthetic headers: never reads or modifies user save slots. */
class SegaSaveStateImportInstrumentedTest {
    private fun <T> isolated(block: (Context, File) -> T): T {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "save-import-qa-${UUID.randomUUID()}"
        val root = File(app.cacheDir, id).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?): File = File(root, "runtime").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$id-$name", mode)
        }
        return try { block(context, root) } finally { root.deleteRecursively() }
    }

    private val genesisName = "JG-GENESIS-ABCD123456789012.01.rstate"
    private fun genesisState() = CoreSaveState.wrap("JGLRSTA1".toByteArray() + byteArrayOf(0, 42), 1)

    @Test fun directImportAndExportedZipRoundTripWithoutChangingBytes() = isolated { context, root ->
        val slots = File(root, "slots")
        val repository = SaveStateRepository(context, slots)
        val bytes = genesisState()
        val source = File(root, genesisName).apply { writeBytes(bytes) }
        val direct = repository.analyzeImport(Uri.fromFile(source))
        assertEquals(1, direct.importableCount)
        assertEquals(1, repository.importStates(direct).importedCount)
        assertArrayEquals(bytes, File(slots, genesisName).readBytes())
        val archive = File(root, "export.zip")
        assertTrue(repository.backupEntries(repository.listEntries(), Uri.fromFile(archive)))
        File(slots, genesisName).delete()
        val zipped = repository.analyzeImport(Uri.fromFile(archive))
        assertEquals(1, zipped.importableCount)
        assertEquals(1, repository.importStates(zipped).importedCount)
        assertArrayEquals(bytes, File(slots, genesisName).readBytes())
    }

    @Test fun selectedGameAcceptsNamespacedStateDespiteProductSerialAndRejectsOtherCores() = isolated { context, root ->
        val repository = SaveStateRepository(context, File(root, "slots"))
        val game = File(root, "game.md").apply { writeBytes(byteArrayOf(0)) }
        val source = File(root, genesisName).apply { writeBytes(genesisState()) }
        val matching = repository.analyzeImport(Uri.fromFile(source), game.path, "GM 00004049-00")
        assertEquals(1, matching.importableCount)
        assertEquals(1, repository.importStates(matching).importedCount)
        val otherConsole = File(root, "game.gg").apply { writeBytes(byteArrayOf(0)) }
        val rejected = repository.analyzeImport(Uri.fromFile(source), otherConsole.path)
        assertEquals(0, rejected.importableCount)
        assertEquals(1, rejected.incompatibleCount)
        repository.discardImport(rejected)
        val flycast = File(root, "MK-51000.01.rstate").apply { writeBytes(CoreSaveState.wrap(byteArrayOf(42), 0)) }
        val wrongCore = repository.analyzeImport(Uri.fromFile(flycast), game.path)
        assertEquals(0, wrongCore.importableCount)
        repository.discardImport(wrongCore)
    }

    @Test fun finalImportRejectsStagedCoreChangedAfterPreview() = isolated { context, root ->
        val slots = File(root, "slots")
        val repository = SaveStateRepository(context, slots)
        val source = File(root, genesisName).apply { writeBytes(genesisState()) }
        val preview = repository.analyzeImport(Uri.fromFile(source))
        assertEquals(1, preview.importableCount)
        File(preview.stagedFilePath).writeBytes(CoreSaveState.wrap(byteArrayOf(42), 0))
        assertEquals(SaveStateImportResult(0, 1), repository.importStates(preview))
        assertFalse(File(slots, genesisName).exists())
    }
}
