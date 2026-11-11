package com.sbro.emucoreh.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamcastBiosTest {
    @Test fun consoleDiscsAndBiosBootRequireFirmwareButArcadeArchivesKeepTheirOwnBios() {
        for (extension in GameFormats.discExtensions + GameFormats.playlistExtensions) {
            assertTrue(DreamcastBios.requiresConsoleBios(false, "GAME.${extension.uppercase()}"))
        }
        for (extension in GameFormats.romExtensions + "bin") {
            assertFalse(DreamcastBios.requiresConsoleBios(false, "game.$extension"))
        }
        assertTrue(DreamcastBios.requiresConsoleBios(true, ""))
    }

    @Test fun missingTruncatedAndArcadeFirmwareCannotSatisfyConsoleLaunch() {
        val root = java.nio.file.Files.createTempDirectory("dreamcast-required-bios").toFile()
        try {
            val dc = DreamcastBios.directory(root.path).apply { mkdirs() }
            assertFalse(DreamcastBios.hasUsableBootRom(root.path))
            File(dc, "dc_flash.bin").writeBytes(ByteArray(DreamcastBios.FLASH_ROM_SIZE.toInt()))
            assertFalse(DreamcastBios.hasUsableBootRom(root.path))
            val boot = File(dc, "dc_boot.bin")
            boot.writeBytes(ByteArray(1024))
            assertFalse(DreamcastBios.hasUsableBootRom(root.path))
            val bytes = ByteArray(DreamcastBios.BOOT_ROM_SIZE.toInt()) { 0x11 }
            "SEGA NAOMI".toByteArray().copyInto(bytes)
            boot.writeBytes(bytes)
            assertFalse(DreamcastBios.hasUsableBootRom(root.path))
            boot.writeBytes(ByteArray(DreamcastBios.BOOT_ROM_SIZE.toInt()).apply {
                "SEGA SEGAKATANA  KABUTO Ver.1.01d Copyright(c) SEGA ENTERPRISES, LTD.".toByteArray().copyInto(this, 0x7b0)
            })
            assertTrue(DreamcastBios.hasUsableBootRom(root.path))
        } finally { root.deleteRecursively() }
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
