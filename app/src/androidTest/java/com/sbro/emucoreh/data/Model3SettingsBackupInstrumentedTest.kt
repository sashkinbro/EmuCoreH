package com.sbro.emucoreh.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.core.SegaControlProfiles
import com.sbro.emucoreh.core.SegaSettingsBackup
import com.sbro.emucoreh.data.drive.DriveBackupArchive
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class Model3SettingsBackupInstrumentedTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "qa-model3-settings-${UUID.randomUUID()}-"
    private val root = File(base.cacheDir, prefix).apply { mkdirs() }
    private val preferenceNames = mutableSetOf<String>()
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            preferenceNames += prefix + name
            return base.getSharedPreferences(prefix + name, mode)
        }
    }
    private val model3 = mapOf(0 to mapOf("cross" to 30, "l2" to 40),
        1 to mapOf("circle" to 31, "r2" to 41, "l3" to 42))
    private val genesis = mapOf(0 to mapOf("cross" to 50, "square" to 51))

    @Before fun keepProcessWideDataStoreOnItsNormalOwner() = runBlocking {
        // Archive export reads global settings but never imports them in this test.
        // Initialize the singleton with the real context before using the file-isolated wrapper.
        AppPreferences(base).exportJson()
        Unit
    }

    @After fun cleanUp() {
        preferenceNames.forEach(base::deleteSharedPreferences)
        root.deleteRecursively()
    }

    @Test fun localArchiveAndSharedRestoreKeepModel3AndGenesisProfiles() = runBlocking {
        installProfiles()
        val output = ByteArrayOutputStream()
        SettingsBackupRepository(context, AppPreferences(context), PerGameSettingsRepository(context),
            CheatRepository(context)).writeBackup(output)
        val settings = ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            var found: JSONObject? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "settings.json") found = JSONObject(zip.readBytes().decodeToString())
            }
            requireNotNull(found)
        }
        verifySharedRestore(settings)
    }

    @Test fun driveArchiveAndSharedRestoreKeepModel3AndGenesisProfiles() = runBlocking {
        installProfiles()
        val snapshot = DriveBackupArchive(context).create(includeTextures = false)
        val settings = ZipFile(snapshot.file).use { zip ->
            JSONObject(zip.getInputStream(zip.getEntry("settings.json")).use { it.readBytes().decodeToString() })
        }
        verifySharedRestore(settings)
    }

    private fun installProfiles() {
        com.sbro.emucoreh.core.SaturnCoreOptions.set(context, "yabasanshiro_frameskip", "enabled")
        com.sbro.emucoreh.core.SaturnCoreOptions.set(context, "yabasanshiro_resolution_mode", "2x")
        SegaControlProfiles.save(context, EmulationPlatform.MODEL_3, model3)
        SegaControlProfiles.save(context, EmulationPlatform.GENESIS, genesis)
        context.getSharedPreferences("legacy-layout-proof", 0).edit().putString("layout", "untouched").commit()
    }

    // Both production restore wrappers call this same nested-settings restore.
    // Avoid importing unrelated process-wide DataStore settings in this isolated profile test.
    private fun verifySharedRestore(settings: JSONObject) {
        val nested = settings.getJSONObject("segaSettings")
        assertEquals("enabled", nested.getJSONObject("coreOptions").getJSONObject("SATURN").getString("yabasanshiro_frameskip"))
        assertTrue(nested.getJSONObject("gamepadProfiles").has(EmulationPlatform.MODEL_3.name))
        assertFalse(nested.getJSONObject("coreOptions").has(EmulationPlatform.MODEL_3.name))
        SegaSettingsBackup.resetAll(context)
        assertTrue(SegaControlProfiles.load(context, EmulationPlatform.MODEL_3).isEmpty())
        SegaSettingsBackup.restore(context, nested)
        assertEquals("enabled", com.sbro.emucoreh.core.SaturnCoreOptions.value(context, "yabasanshiro_frameskip"))
        assertEquals("2x", com.sbro.emucoreh.core.SaturnCoreOptions.value(context, "yabasanshiro_resolution_mode"))
        assertEquals(model3, SegaControlProfiles.load(context, EmulationPlatform.MODEL_3))
        assertEquals(genesis, SegaControlProfiles.load(context, EmulationPlatform.GENESIS))
        assertEquals("untouched", context.getSharedPreferences("legacy-layout-proof", 0).getString("layout", null))
    }
}
