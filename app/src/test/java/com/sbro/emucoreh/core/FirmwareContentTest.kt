package com.sbro.emucoreh.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.*
import org.junit.Test

class FirmwareContentTest {
    private fun consoleBoot() = ByteArray(DreamcastBios.BOOT_ROM_SIZE.toInt()).apply {
        "SEGA SEGAKATANA  KABUTO Ver.1.01d Copyright(c) SEGA ENTERPRISES, LTD.".toByteArray().copyInto(this, 0x7b0)
    }
    private fun cdBoot(region: Char) = ByteArray(SegaCdBios.SIZE).apply {
        "SEGA MEGA DRIVE ".toByteArray().copyInto(this, 0x100)
        "CD2 BOOT ROM".toByteArray().copyInto(this, 0x120)
        this[0x1f0] = region.code.toByte()
    }
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { buffer ->
        ZipOutputStream(buffer).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun misleadingNamesAndExtensionsCannotChooseConsoleOrRegion() {
        val roms = FirmwareContent.readSelection(zip("naomi.zip" to consoleBoot(),
            "dc_boot.bin" to cdBoot('U'), "bios_CD_U.bin" to cdBoot('J')).inputStream())
        assertEquals(setOf("dc_boot.bin", "bios_CD_U.bin", "bios_CD_J.bin"), roms.map { it.name }.toSet())
        assertEquals(1, roms.count { it.family == FirmwareContent.Family.DREAMCAST })
        assertEquals(2, roms.count { it.family == FirmwareContent.Family.SEGA_CD })
        assertTrue(roms.none { it.family == FirmwareContent.Family.ARCADE })
    }

    @Test fun randomCorrectlySizedFilesAndEmptyArchivesAreNotFirmware() {
        for (size in listOf(256, 1024, 2048, 65536, 131072, 524288, 2097152)) {
            assertTrue("Random $size bytes", FirmwareContent.identify(ByteArray(size) { 0x55 }).isEmpty())
        }
        assertTrue(FirmwareContent.readSelection(zip("naomi.ic27" to ByteArray(2097152)).inputStream()).isEmpty())
        assertTrue(FirmwareContent.readSelection(zip().inputStream()).isEmpty())
    }

    @Test fun flashAndCdWithTheSameSizeStaySeparate() {
        val flash = ByteArray(131072).apply {
            "Dreamcast  ".toByteArray().copyInto(this, 0x1a005)
            "Dreamcast  ".toByteArray().copyInto(this, 0x1a0a5)
        }
        assertEquals(DreamcastBios.FLASH_FILE_NAME, FirmwareContent.identify(flash).single().name)
        assertEquals(FirmwareContent.Family.SEGA_CD, FirmwareContent.identify(cdBoot('E')).single().family)
        assertTrue(FirmwareContent.identify(flash.copyOf(131071)).isEmpty())
    }

    @Test fun sevenZipUsesContentEvenWithWrongMemberName() {
        val file = File.createTempFile("firmware-test-", ".data")
        try {
            SevenZOutputFile(file).use { output ->
                val entry = org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry()
                entry.name = "bios_CD_E.bin"
                output.putArchiveEntry(entry)
                output.write(consoleBoot())
                output.closeArchiveEntry()
            }
            assertEquals("dc_boot.bin", file.inputStream().use(FirmwareContent::readSelection).single().name)
        } finally { file.delete() }
    }

    @Test fun overexpandedArchiveIsRejectedBeforeAnyFirmwareIsPublished() {
        val archive = zip("valid.bin" to cdBoot('U'), "oversized.bin" to ByteArray(FirmwareContent.MAX_ROM + 1))
        assertThrows(IllegalStateException::class.java) { FirmwareContent.readSelection(archive.inputStream()) }
    }

    @Test fun directoryNamesNeverBecomeOutputPaths() {
        val identified = FirmwareContent.readSelection(zip("../../dc_boot.bin" to cdBoot('J')).inputStream())
        assertEquals("bios_CD_J.bin", identified.single().name)
        assertNull(identified.single().archive)
    }
}
