package com.sbro.emucoreh.core

import com.sbro.emucoreh.data.SegaTouchLayouts
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SaturnIntegrationTest {
    @Test fun saturnHasIndependentCoreAndCompleteDigitalPad() {
        val saturn = EmulationPlatform.SATURN
        assertEquals(3, saturn.coreId)
        assertFalse(saturn.isSega)
        assertTrue(saturn.usesPlatformTouchLayouts)
        assertTrue(saturn.supportsDisc)
        assertEquals(setOf("A", "B", "C", "X", "Y", "Z", "L", "R", "Start"),
            SegaTouchLayouts.controlIds(saturn).mapNotNull { SegaTouchLayouts.label(saturn, it) }.toSet())
        assertTrue(SegaControlProfiles.supports(saturn, "l2"))
        assertTrue(SegaControlProfiles.supports(saturn, "r2"))
        assertFalse(SegaControlProfiles.supports(saturn, "select"))
        assertFalse(SegaControlProfiles.supports(saturn, "left_stick_up"))
    }

    @Test fun cueAndPlaylistIdentifySaturnWithoutAFileNameHint() {
        val directory = Files.createTempDirectory("saturn-detector").toFile()
        try {
            File(directory, "track.bin").writeBytes(ByteArray(32768).apply {
                "SEGA SEGASATURN".toByteArray().copyInto(this, 16)
            })
            File(directory, "disc.cue").writeText("FILE \"track.bin\" BINARY\n TRACK 01 MODE1/2352\n INDEX 01 00:00:00")
            val playlist = File(directory, "game.m3u").apply { writeText("disc.cue\n") }
            assertEquals(EmulationPlatform.SATURN, SegaPlatformDetector.detectFile(playlist))
        } finally { directory.deleteRecursively() }
    }

    @Test fun biosRejectsArbitraryImagesAndBoundedOversizedStreams() {
        assertFalse(SaturnBios.isValid(ByteArray(SaturnBios.SIZE)))
        assertNull(SaturnBios.readValidated(ByteArray(SaturnBios.SIZE + 1).inputStream()))
        assertNull(SaturnBios.readValidated(ByteArray(32).inputStream()))
    }

    @Test fun backupAllowsOnlyActualInternalRamAndNeverFirmwareOrTraversal() {
        assertTrue(SaturnSaveFiles.isBackupPath("Panzer Dragoon.bkr"))
        assertTrue(SaturnSaveFiles.isBackupPath("Panzer Dragoon.smpc"))
        assertTrue(SaturnSaveFiles.isBackupPath("Panzer Dragoon.bcr"))
        for (path in listOf("../system/saturn_bios.bin", "saturn_bios.bin", "game/backup.bkr",
            "game\\backup.bkr", ".hidden.bkr", "backup.bin", "backup.bkr.exe")) {
            assertFalse(path, SaturnSaveFiles.isBackupPath(path))
        }
    }
}
