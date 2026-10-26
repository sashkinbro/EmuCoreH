package com.sbro.emucoreh.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucoreh.data.drive.DriveBackupArchive
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SaturnBackupInstrumentedTest {
    @Test fun driveExtractsEightMiBRamAndRejectsLargerFilesBeforeChangingUserData() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "saturn-backup-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
        }
        try {
            val archive = DriveBackupArchive(context)
            val payload = ByteArray(8 * 1024 * 1024) { (it * 37).toByte() }
            val snapshot = File(root, "valid.zip")
            writeSnapshot(snapshot, payload)
            archive.extract(snapshot)
            val restored = File(archive.workDir, "extracted/saturn-saves/yabasanshiro/backup.bin")
            assertArrayEquals(payload, restored.readBytes())
            val oversized = File(root, "oversized.zip")
            writeSnapshot(oversized, payload + byteArrayOf(0))
            assertTrue(runCatching { archive.extract(oversized) }.isFailure)
        } finally { root.deleteRecursively() }
    }

    private fun writeSnapshot(target: File, payload: ByteArray) {
        val files = linkedMapOf(
            "settings.json" to "{}".toByteArray(),
            "per-game.json" to "{}".toByteArray(),
            "cheats.json" to "{}".toByteArray(),
            "saturn-saves/yabasanshiro/backup.bin" to payload
        )
        val manifest = JSONObject().put("format", DriveBackupArchive.FORMAT).put("schema", 1)
            .put("files", JSONArray().apply {
                files.forEach { (name, bytes) -> put(JSONObject().put("name", name).put("size", bytes.size)
                    .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })) }
            })
        ZipOutputStream(target.outputStream()).use { zip ->
            files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
        }
    }
}
