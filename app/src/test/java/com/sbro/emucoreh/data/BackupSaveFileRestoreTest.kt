package com.sbro.emucoreh.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupSaveFileRestoreTest {
    @Test fun corruptAndTruncatedZipEntriesPreserveInstalledSaveHashes() {
        val payload = ByteArray(4096) { (it * 37).toByte() }
        val archive = archive(payload)
        // STORED entry exposes payload directly; its CRC remains unchanged.
        val payloadOffset = 30 + "battery-saves/genesis/game.sav".toByteArray().size
        val corrupt = archive.copyOf().also { it[payloadOffset + 20] = (it[payloadOffset + 20].toInt() xor 1).toByte() }
        for (name in listOf("installed.rstate", "game.sav", "vmu_save_A1.bin", "game.pnach")) {
            for (bytes in listOf(corrupt, archive.copyOf(payloadOffset + payload.size / 2))) {
                withTarget(name) { target ->
                    val hash = hash(target)
                    val failure = runCatching { ZipInputStream(bytes.inputStream()).use { zip ->
                        requireNotNull(zip.nextEntry)
                        restoreBackupSaveFile(zip, target, 8192, allowEmpty = false)
                    } }.exceptionOrNull()
                    assertNotNull("ZIP failure is reported", failure)
                    assertEquals("Installed save survives failed restore", hash, hash(target))
                    assertEquals(listOf(target.name), target.parentFile!!.listFiles()!!.map { it.name })
                }
            }
        }
    }

    @Test fun emptyCheatConfigurationsRemainRestorable() {
        withTarget("game.pnach") { target ->
            restoreBackupSaveFile(ByteArray(0).inputStream(), target, 16L * 1024 * 1024, allowEmpty = true)
            assertTrue(target.isFile)
            assertEquals(0L, target.length())
        }
    }

    @Test fun boundedCompleteEntryReplacesSaveAndOversizedOrEmptyEntriesDoNot() {
        for (name in listOf("installed.rstate", "game.sav", "vmu_save_A1.bin", "game.pnach")) withTarget(name) { target ->
            val original = hash(target)
            for (payload in listOf(ByteArray(2049), ByteArray(0))) {
                assertTrue(runCatching { restoreBackupSaveFile(payload.inputStream(), target, 2048, allowEmpty = false) }.isFailure)
                assertEquals(original, hash(target))
            }
            val bytes = ByteArray(2048) { (it xor 42).toByte() }
            ZipInputStream(archive(bytes).inputStream()).use { zip ->
                requireNotNull(zip.nextEntry)
                restoreBackupSaveFile(zip, target, 2048, allowEmpty = false)
            }
            assertArrayEquals(bytes, target.readBytes())
            assertEquals(listOf(target.name), target.parentFile!!.listFiles()!!.map { it.name })
        }
    }

    private fun archive(payload: ByteArray) = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            val entry = ZipEntry("battery-saves/genesis/game.sav").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = size
                crc = CRC32().also { it.update(payload) }.value
            }
            zip.putNextEntry(entry); zip.write(payload); zip.closeEntry()
        }
    }.toByteArray()

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun withTarget(name: String, block: (File) -> Unit) {
        val directory = Files.createTempDirectory("backup-save-restore-test").toFile()
        try { block(File(directory, name).apply { writeText("installed valid save") }) }
        finally { directory.deleteRecursively() }
    }
}
