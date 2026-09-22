package com.sbro.emucoreh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(
            "dc_flash.bin",
            DreamcastBios.supportedBiosTargetName("dc_flash.bin", DreamcastBios.FLASH_ROM_SIZE)
        )
        assertEquals("dc_boot.bin", DreamcastBios.supportedBiosTargetName("dc_boot.bin", -1L))
        assertEquals("dc_flash.bin", DreamcastBios.supportedBiosTargetName("dc_flash.bin", 0L))
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
            "dc_flash.bin",
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
}
