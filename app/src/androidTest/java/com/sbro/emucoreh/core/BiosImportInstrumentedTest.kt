package com.sbro.emucoreh.core

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiosImportInstrumentedTest {
    @Test fun descriptiveSegaCdNamesAreImportedByTheirActualRegion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "bios-import-descriptive-qa").apply { mkdirs() }
        try {
            val source = File(root, "source").apply { mkdirs() }
            val destination = File(root, "system")
            listOf('U' to "Sega-CD Model 2 BIOS V2.11 (U) [b1].bin",
                'E' to "Mega-CD Model 2 BIOS V2.00 (E) (Mar 1993) [b1].bin").forEach { (region, name) ->
                File(source, name).writeBytes(ByteArray(SegaCdBios.SIZE).apply {
                    "SEGA MEGA DRIVE ".toByteArray().copyInto(this, 0x100)
                    "CD2 BOOT ROM".toByteArray().copyInto(this, 0x120)
                    this[0x1f0] = region.code.toByte()
                })
            }
            // A Dreamcast flash file has the same size and must not be mistaken for CD firmware.
            File(source, "dc_flash.bin").writeBytes(ByteArray(SegaCdBios.SIZE))
            val result = BiosImport.installFromDocument(context, DocumentFile.fromFile(source),
                File(root, "flycast").absolutePath, destination.absolutePath)
            assertEquals(2, result.segaCd)
            assertEquals(1, result.dreamcast)
            assertEquals(SegaCdBios.Region.US, SegaCdBios.detectRegion(File(destination, "bios_CD_U.bin").readBytes()))
            assertEquals(SegaCdBios.Region.EU, SegaCdBios.detectRegion(File(destination, "bios_CD_E.bin").readBytes()))
        } finally { root.deleteRecursively() }
    }

    @Test fun oneFolderImportsDreamcastArcadeSegaCdAnd32X() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "bios-import-qa").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val source = File(root, "bios").apply { mkdirs() }
            val flycast = File(root, "flycast").apply { mkdirs() }
            val sega = File(root, "jgenesis").apply { mkdirs() }
            source.resolve("dc_boot.bin").writeBytes(ByteArray(DreamcastBios.BOOT_ROM_SIZE.toInt()) { 0x11 })
            source.resolve("dc_flash.bin").writeBytes(ByteArray(DreamcastBios.FLASH_ROM_SIZE.toInt()) { 0x22 })
            source.resolve("naomi.zip").writeBytes(ByteArray(1024) { 0x5a })
            source.resolve("bios_CD_U.bin").writeBytes(ByteArray(SegaCdBios.SIZE) { 0x33 })
            source.resolve("bios_CD_J.bin").writeBytes(ByteArray(SegaCdBios.SIZE) { 0x44 })
            val firmware32x = find32xFirmware(context)
            if (firmware32x != null) {
                Sega32XBios.Part.entries.forEach { part ->
                    File(firmware32x, part.fileName).copyTo(File(source, part.fileName))
                }
            }

            val result = BiosImport.installFromDocument(
                context,
                DocumentFile.fromFile(source),
                flycast.absolutePath,
                sega.absolutePath
            )

            assertEquals(2, result.dreamcast)
            assertEquals(1, result.arcade)
            assertEquals(2, result.segaCd)
            assertEquals(if (firmware32x != null) 3 else 0, result.sega32x)
            assertTrue(File(flycast, "dc/dc_boot.bin").isFile)
            assertTrue(File(flycast, "dc/dc_flash.bin").isFile)
            assertTrue(File(flycast, "dc/naomi.zip").isFile)
            assertTrue(File(sega, "bios_CD_U.bin").isFile)
            assertTrue(File(sega, "bios_CD_J.bin").isFile)
            if (firmware32x != null) {
                Sega32XBios.Part.entries.forEach { part ->
                    assertArrayEquals(File(firmware32x, part.fileName).readBytes(),
                        File(sega, part.fileName).readBytes())
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun renamed32xDumpsAreIdentifiedBySizeAndCrc() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firmware = find32xFirmware(context) ?: return
        val root = File(context.cacheDir, "bios-import-renamed-qa").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val source = File(root, "bios").apply { mkdirs() }
            val sega = File(root, "jgenesis").apply { mkdirs() }
            Sega32XBios.Part.entries.forEachIndexed { index, part ->
                File(firmware, part.fileName).copyTo(File(source, "dump-$index.rom"))
            }
            val result = BiosImport.installFromDocument(
                context,
                DocumentFile.fromFile(source),
                File(root, "flycast").absolutePath,
                sega.absolutePath
            )
            assertEquals(3, result.sega32x)
            Sega32XBios.Part.entries.forEach { part ->
                assertArrayEquals(File(firmware, part.fileName).readBytes(),
                    File(sega, part.fileName).readBytes())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun invalidSegaFirmwareDoesNotReplaceInstalledFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "bios-import-invalid-qa").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val source = File(root, "bios").apply { mkdirs() }
            val flycast = File(root, "flycast").apply { mkdirs() }
            val sega = File(root, "jgenesis").apply { mkdirs() }
            val existingCd = ByteArray(SegaCdBios.SIZE) { 0x66 }
            File(sega, "bios_CD_E.bin").writeBytes(existingCd)
            source.resolve("bios_CD_E.bin").writeBytes(ByteArray(SegaCdBios.SIZE - 1))
            val firmware32x = find32xFirmware(context)
            if (firmware32x != null) {
                Sega32XBios.Part.entries.forEach { part ->
                    File(firmware32x, part.fileName).copyTo(File(sega, part.fileName))
                }
                source.resolve("32X_G_BIOS.bin").writeBytes(ByteArray(256))
            }

            val result = BiosImport.installFromDocument(
                context,
                DocumentFile.fromFile(source),
                flycast.absolutePath,
                sega.absolutePath
            )

            assertEquals(0, result.segaCd)
            assertEquals(0, result.sega32x)
            assertArrayEquals(existingCd, File(sega, "bios_CD_E.bin").readBytes())
            if (firmware32x != null) {
                assertArrayEquals(File(firmware32x, "32X_G_BIOS.bin").readBytes(),
                    File(sega, "32X_G_BIOS.bin").readBytes())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun find32xFirmware(context: Context): File? =
        listOf(File(context.filesDir, "qa-32x-bios"), File(context.filesDir, "jgenesis/system"))
            .firstOrNull { directory ->
                Sega32XBios.Part.entries.all { File(directory, it.fileName).isFile }
            }
}
