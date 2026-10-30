package com.sbro.emucoreh.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamcastBiosTest {
    @Test fun acceptsDreamcastBootAndFlashDumps() {
        assertEquals(
            "dc_boot.bin",
            DreamcastBios.supportedBiosTargetName("dc_boot.bin", DreamcastBios.BOOT_ROM_SIZE)
        )
        assertEquals(
            "dc_bios.bin",
            DreamcastBios.supportedBiosTargetName("dc_bios.bin", DreamcastBios.BOOT_ROM_SIZE)
        )
        // The core opens the flash as dc_nvmem.bin; the standalone name is
        // accepted on import and stored under the name the core reads.
        assertEquals(
            "dc_nvmem.bin",
            DreamcastBios.supportedBiosTargetName("dc_flash.bin", DreamcastBios.FLASH_ROM_SIZE)
        )
        assertEquals(
            "dc_nvmem.bin",
            DreamcastBios.supportedBiosTargetName("dc_nvmem.bin", DreamcastBios.FLASH_ROM_SIZE)
        )
        assertEquals("dc_boot.bin", DreamcastBios.supportedBiosTargetName("dc_boot.bin", -1L))
        assertEquals("dc_nvmem.bin", DreamcastBios.supportedBiosTargetName("dc_flash.bin", 0L))
    }

    @Test fun acceptsDescriptiveDumpNamesBySize() {
        assertEquals(
            "dc_boot.bin",
            DreamcastBios.supportedBiosTargetName(
                "Sega Dreamcast BIOS v1.01d (1998)(Sega)(Eu).bin",
                DreamcastBios.BOOT_ROM_SIZE
            )
        )
        assertEquals(
            "dc_nvmem.bin",
            DreamcastBios.supportedBiosTargetName("vmu flash dump.bin", DreamcastBios.FLASH_ROM_SIZE)
        )
        assertEquals(
            "dc_boot.bin",
            DreamcastBios.supportedBiosTargetName("Sega BIOS dump", DreamcastBios.BOOT_ROM_SIZE)
        )
    }

    @Test fun rejectsBiosNamedFilesWithUnexpectedSizes() {
        assertNull(DreamcastBios.supportedBiosTargetName("dc_boot.bin", 1024L))
        assertNull(DreamcastBios.supportedBiosTargetName("dc_flash.bin", DreamcastBios.BOOT_ROM_SIZE))
        assertNull(DreamcastBios.supportedBiosTargetName("dc_nvmem.bin", DreamcastBios.BOOT_ROM_SIZE))
    }

    @Test fun acceptsArcadeBiosSetsRegardlessOfCaseAndSize() {
        assertEquals(
            "naomi.zip",
            DreamcastBios.supportedBiosTargetName("naomi.zip", 4L * 1024L * 1024L)
        )
        assertEquals("naomi2.zip", DreamcastBios.supportedBiosTargetName("NAOMI2.ZIP", 1L))
        assertEquals("awbios.7z", DreamcastBios.supportedBiosTargetName("awbios.7z", -1L))
    }

    @Test fun ignoresUnrelatedFiles() {
        assertNull(DreamcastBios.supportedBiosTargetName("sonic.gdi", 1024L))
        assertNull(DreamcastBios.supportedBiosTargetName("random.zip", 1024L))
        assertNull(DreamcastBios.supportedBiosTargetName(null, 1024L))
    }

    @Test fun segaBiosNamesAreNeverImportedAsDreamcastDumps() {
        assertNull(DreamcastBios.supportedBiosTargetName("bios_CD_U.bin", DreamcastBios.FLASH_ROM_SIZE))
        assertNull(DreamcastBios.supportedBiosTargetName("bios_cd_jp.bin", DreamcastBios.FLASH_ROM_SIZE))
        assertNull(DreamcastBios.supportedBiosTargetName("32X_G_BIOS.bin", 256L))
        assertNull(DreamcastBios.supportedBiosTargetName("32X_M_BIOS.bin", 2048L))
    }

    @Test fun arcadeFirmwareIsNeverUsedAsTheDreamcastBootRom() {
        // A size-only import can pick up a Naomi or Atomiswave 2 MiB boot ROM;
        // those dumps carry their own product names.
        assertFalse(DreamcastBios.looksLikeArcadeBootRom(ByteArray(4096) { 0x5A }))
        assertFalse(DreamcastBios.looksLikeArcadeBootRom(ByteArray(0)))
        assertTrue(
            DreamcastBios.looksLikeArcadeBootRom(
                "SEGA NAOMI BOOT ROM".toByteArray() + ByteArray(1024)
            )
        )
        assertTrue(
            DreamcastBios.looksLikeArcadeBootRom(
                "SAMMY ATOMISWAVE BIOS".toByteArray() + ByteArray(1024)
            )
        )
        assertTrue(DreamcastBios.looksLikeArcadeBootRom("sega naomi".toByteArray()))
    }

    @Test fun installedArcadeFirmwareIsDetectedOnDisk() {
        val rom = File.createTempFile("dc-boot", ".bin")
        try {
            rom.writeBytes("SEGA NAOMI".toByteArray() + ByteArray(1024))
            assertTrue(DreamcastBios.looksLikeArcadeBootRom(rom))
            rom.writeBytes(ByteArray(2048) { 0x11 })
            assertFalse(DreamcastBios.looksLikeArcadeBootRom(rom))
            assertFalse(DreamcastBios.looksLikeArcadeBootRom(File("missing-boot-rom.bin")))
        } finally {
            rom.delete()
        }
    }
}
