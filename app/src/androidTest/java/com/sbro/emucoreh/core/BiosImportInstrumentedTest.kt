package com.sbro.emucoreh.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
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
    @Test fun selectedBiosIsNotOpenedAgainWhenOnboardingHandsOffToHome() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "bios-import-handoff-${System.nanoTime()}"
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$prefix-$name", mode)
        }
        try {
            val source = File(root, "bios_CD_U.bin").apply {
                writeBytes(ByteArray(SegaCdBios.SIZE).apply {
                    "SEGA MEGA DRIVE ".toByteArray().copyInto(this, 0x100)
                    "CD2 BOOT ROM".toByteArray().copyInto(this, 0x120)
                    this[0x1f0] = 'U'.code.toByte()
                })
            }
            val uri = Uri.fromFile(source)
            assertEquals(1, BiosImport.installSelection(context, uri).segaCd)
            // A second provider open now fails. Completed handoff proves no reopen
            // was needed, including after a new history instance reads disk prefs.
            assertTrue(source.delete())
            repeat(2) { BiosImport.restoreSelections(context, listOf(uri.toString())) }
            assertEquals(BiosImportHistory.selectionKey(listOf(uri.toString())),
                context.getSharedPreferences("bios_import_migrations", Context.MODE_PRIVATE)
                    .getString("completed", null))
            assertTrue(File(SegaCdBios.directory(context), "bios_CD_U.bin").isFile)
        } finally {
            base.deleteSharedPreferences("$prefix-bios_import_migrations")
            root.deleteRecursively()
        }
    }

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
            File(source, "dc_flash.bin").writeBytes(flashFixture())
            val result = BiosImport.installFromDocument(context, DocumentFile.fromFile(source),
                File(root, "flycast").absolutePath, destination.absolutePath)
            assertEquals(2, result.segaCd)
            assertEquals(1, result.dreamcast)
            assertEquals(SegaCdBios.Region.US, SegaCdBios.detectRegion(File(destination, "bios_CD_U.bin").readBytes()))
            assertEquals(SegaCdBios.Region.EU, SegaCdBios.detectRegion(File(destination, "bios_CD_E.bin").readBytes()))
        } finally { root.deleteRecursively() }
    }

    @Test fun oneFolderRoutesConsoleFirmwareAndRejectsUnrecognizedArchive() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "bios-import-qa").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val source = File(root, "bios").apply { mkdirs() }
            val flycast = File(root, "flycast").apply { mkdirs() }
            val sega = File(root, "jgenesis").apply { mkdirs() }
            source.resolve("dc_boot.bin").writeBytes(ByteArray(DreamcastBios.BOOT_ROM_SIZE.toInt()).apply {
                "SEGA SEGAKATANA  KABUTO Ver.1.01d Copyright(c) SEGA ENTERPRISES, LTD.".toByteArray().copyInto(this, 0x7b0)
            })
            source.resolve("dc_flash.bin").writeBytes(flashFixture())
            source.resolve("naomi.zip").writeBytes(ByteArray(1024) { 0x5a })
            source.resolve("bios_CD_U.bin").writeBytes(cdFixture('U'))
            source.resolve("bios_CD_J.bin").writeBytes(cdFixture('J'))
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
            assertEquals(0, result.arcade)
            assertEquals(2, result.segaCd)
            assertEquals(if (firmware32x != null) 3 else 0, result.sega32x)
            assertTrue(File(flycast, "dc/dc_boot.bin").isFile)
            assertTrue(File(flycast, "dc/dc_nvmem.bin").isFile)
            org.junit.Assert.assertFalse(File(flycast, "dc/naomi.zip").isFile)
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

    @Test fun renamedOwnerFirmwareCannotCrossConsoleOrArcadeSlots() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ownerPath = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("releaseQaBios")
            ?: context.contentResolver.persistedUriPermissions.firstOrNull {
                it.uri.toString().contains("bios", ignoreCase = true)
            }?.uri?.toString()
        org.junit.Assume.assumeTrue("Owner BIOS folder selected in app", ownerPath != null)
        val owners = DocumentFile.fromTreeUri(context, Uri.parse(requireNotNull(ownerPath)))!!
        val root = File(context.cacheDir, "bios-content-routing-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source = File(root, "renamed").apply { mkdirs() }
            val dc = owners.findFile("Sega Dreamcast BIOS v1.01d (1998)(Sega)(Eu).bin")!!
            val naomi = owners.findFile("naomi.zip")!!
            val naomi2 = owners.findFile("naomi2.zip")!!
            val aw = owners.findFile("awbios.zip")!!
            org.junit.Assume.assumeTrue("Owner console and arcade BIOS", listOf(dc, naomi, naomi2, aw).all { it.isFile })
            fun bytes(file: DocumentFile) = context.contentResolver.openInputStream(file.uri)!!.use { it.readBytes() }
            File(source, "naomi.zip").writeBytes(bytes(dc))
            File(source, "dc_boot.bin").writeBytes(bytes(naomi))
            File(source, "32X_G_BIOS.bin").writeBytes(bytes(naomi2))
            File(source, "saturn_bios.bin").writeBytes(bytes(aw))
            val flycast = File(root, "system")
            val result = BiosImport.installFromDocument(context, DocumentFile.fromFile(source),
                flycast.path, File(root, "sega").path)
            assertEquals(1, result.dreamcast)
            assertTrue(result.arcade >= 3)
            assertEquals(0, result.sega32x)
            assertEquals(0, result.saturn)
            assertArrayEquals(bytes(dc), File(flycast, "dc/dc_boot.bin").readBytes())
            for (set in listOf("naomi", "naomi2", "awbios")) {
                val detected = File(flycast, "dc/$set.zip").inputStream().use(FirmwareContent::readSelection)
                assertTrue("$set has its own recognized firmware", detected.any { it.archive == set })
                assertTrue("No Dreamcast ROM in $set", detected.none { it.family == FirmwareContent.Family.DREAMCAST })
            }
            val sevenZip = File(root, "renamed-collection.data")
            org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(sevenZip).use { output ->
                val entry = org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry()
                entry.name = "naomi2.zip"
                output.putArchiveEntry(entry)
                output.write(bytes(dc))
                output.closeArchiveEntry()
            }
            val sevenResult = BiosImport.installSelection(context, Uri.fromFile(sevenZip),
                File(root, "seven-system").path, File(root, "seven-sega").path)
            assertEquals(1, sevenResult.dreamcast)
            assertEquals(0, sevenResult.arcade)
            // A corrupt replacement with a correct label must leave the known firmware intact.
            File(source, "naomi.zip").writeBytes(ByteArray(DreamcastBios.BOOT_ROM_SIZE.toInt()))
            BiosImport.installFromDocument(context, DocumentFile.fromFile(source), flycast.path, File(root, "sega").path)
            assertArrayEquals(bytes(dc), File(flycast, "dc/dc_boot.bin").readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun renamedSaturnRegionsInstallIndependentlyAndLegacyFirmwareRemainsValid() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val ownerPath = base.contentResolver.persistedUriPermissions.firstOrNull {
            it.uri.toString().contains("bios", ignoreCase = true)
        }?.uri
        org.junit.Assume.assumeTrue("Owner BIOS folder selected", ownerPath != null)
        val owners = DocumentFile.fromTreeUri(base, requireNotNull(ownerPath))!!
        val jpSource = owners.findFile("Sega Saturn BIOS (J) (1.01).bin")
        val euSource = owners.findFile("Sega Saturn BIOS (E) (1.00).bin")
        org.junit.Assume.assumeTrue("Both owner Saturn regions", jpSource != null && euSource != null)
        val jp = base.contentResolver.openInputStream(jpSource!!.uri)!!.use { it.readBytes() }
        val eu = base.contentResolver.openInputStream(euSource!!.uri)!!.use { it.readBytes() }
        val root = File(base.cacheDir, "saturn-region-import-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        }
        try {
            assertEquals("sega_101.bin", SaturnBios.canonicalFileName(jp))
            assertEquals("mpr-17933.bin", SaturnBios.canonicalFileName(eu))
            val system = SaturnBios.directory(context).apply { mkdirs() }
            val legacy = File(system, SaturnBios.FILE_NAME).apply { writeBytes(jp) }
            assertTrue("Legacy file continues to satisfy setup", SaturnBios.isInstalled(context))
            val selected = File(root, "collection").apply { mkdirs() }
            // Names deliberately describe other consoles and the opposite region.
            File(selected, "dc_boot.bin").writeBytes(eu)
            File(selected, "bios_CD_U.bin").writeBytes(jp)
            val imported = BiosImport.installFromDocument(context, DocumentFile.fromFile(selected),
                File(root, "flycast").path, File(root, "sega").path)
            assertEquals(2, imported.saturn)
            assertEquals(0, imported.dreamcast)
            assertEquals(0, imported.segaCd)
            assertArrayEquals(jp, File(system, "sega_101.bin").readBytes())
            assertArrayEquals(eu, File(system, "mpr-17933.bin").readBytes())
            assertArrayEquals("Import never mutates legacy firmware", jp, legacy.readBytes())
            assertTrue(legacy.delete())
            assertTrue("Region files alone satisfy setup", SaturnBios.isInstalled(context))
            assertTrue(File(SaturnBios.requireInstalled(context)).isFile)
            File(selected, "dc_boot.bin").writeBytes(ByteArray(SaturnBios.SIZE))
            BiosImport.installFromDocument(context, DocumentFile.fromFile(selected),
                File(root, "flycast").path, File(root, "sega").path)
            assertArrayEquals(eu, File(system, "mpr-17933.bin").readBytes())
            File(system, "sega_101.bin").writeBytes(eu)
            File(system, "mpr-17933.bin").delete()
            org.junit.Assert.assertFalse("Wrong region in canonical slot is invalid", SaturnBios.isInstalled(context))
        } finally { root.deleteRecursively() }
    }

    @Test fun renamedOwnerSystemSpArchiveCannotReplaceNaomiOrDreamcastFirmware() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ownerPath = context.contentResolver.persistedUriPermissions.firstOrNull {
            it.uri.toString().contains("bios", ignoreCase = true)
        }?.uri
        org.junit.Assume.assumeTrue("Owner BIOS folder selected", ownerPath != null)
        val source = DocumentFile.fromTreeUri(context, requireNotNull(ownerPath))?.findFile("segasp.zip")
        org.junit.Assume.assumeTrue("Owner System SP firmware supplied", source != null)
        val root = File(context.cacheDir, "bios-systemsp-routing-${System.nanoTime()}").apply { mkdirs() }
        try {
            val disguised = File(root, "dc_boot.bin")
            context.contentResolver.openInputStream(requireNotNull(source).uri)!!.use { input ->
                disguised.outputStream().use(input::copyTo)
            }
            val system = File(root, "system")
            val result = BiosImport.installSelection(context, Uri.fromFile(disguised), system.path,
                File(root, "sega").path)
            assertTrue("System SP content imported despite Dreamcast filename", result.arcade > 0)
            assertEquals(0, result.dreamcast)
            val installed = File(system, "dc/segasp.zip")
            assertTrue(installed.isFile)
            val detected = installed.inputStream().use(FirmwareContent::readSelection)
            assertTrue(detected.isNotEmpty())
            assertTrue("Only System SP firmware in its canonical archive", detected.all { it.archive == "segasp" })
            java.util.zip.ZipFile(disguised).use { original ->
                java.util.zip.ZipFile(installed).use { canonical ->
                    for (name in listOf("mb_eeprom_us.ic54s", "mb_eeprom_exp.ic54s")) {
                        val entry = requireNotNull(canonical.getEntry(name)) { "Regional System SP EEPROM missing: $name" }
                        assertEquals("System SP EEPROM is 128 bytes", 128L, entry.size)
                        assertArrayEquals(original.getInputStream(original.getEntry(name)).use { it.readBytes() },
                            canonical.getInputStream(entry).use { it.readBytes() })
                    }
                }
            }
            for (name in listOf("dc_boot.bin", "naomi.zip", "naomi2.zip", "awbios.zip")) {
                org.junit.Assert.assertFalse("System SP must not create $name", File(system, "dc/$name").exists())
            }
        } finally { root.deleteRecursively() }
    }

    private fun flashFixture() = ByteArray(SegaCdBios.SIZE).apply {
        "Dreamcast  ".toByteArray().copyInto(this, 0x1a005)
        "Dreamcast  ".toByteArray().copyInto(this, 0x1a0a5)
    }
    private fun cdFixture(region: Char) = ByteArray(SegaCdBios.SIZE).apply {
        "SEGA MEGA DRIVE ".toByteArray().copyInto(this, 0x100)
        "CD2 BOOT ROM".toByteArray().copyInto(this, 0x120)
        this[0x1f0] = region.code.toByte()
    }

    private fun find32xFirmware(context: Context): File? =
        listOf(File(context.filesDir, "qa-32x-bios"), File(context.filesDir, "jgenesis/system"))
            .firstOrNull { directory ->
                Sega32XBios.Part.entries.all { File(directory, it.fileName).isFile }
            }
}
