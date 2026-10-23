package com.sbro.emucoreh.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class SegaPlatformDetectorTest {
    @Test fun explicitThirtyTwoXExtensionSelectsExpansionForGenesisCompatibleHeaders() {
        assertEquals(EmulationPlatform.SEGA_32X,
            SegaPlatformDetector.detectHeader("smoke.32x", header(0x100, "SEGA MEGA DRIVE")))
    }
    @Test fun cartridgeExtensionsHaveExplicitPlatforms() {
        mapOf("md" to EmulationPlatform.GENESIS, "gen" to EmulationPlatform.GENESIS,
            "smd" to EmulationPlatform.GENESIS, "32x" to EmulationPlatform.SEGA_32X,
            "sms" to EmulationPlatform.MASTER_SYSTEM, "gg" to EmulationPlatform.GAME_GEAR,
            "sg" to EmulationPlatform.SG_1000).forEach { (ext, platform) ->
            assertEquals(platform, SegaPlatformDetector.detectHeader("Game.$ext", byteArrayOf()))
            assertTrue(GameFormats.isSupportedName("Game.$ext"))
        }
    }
    @Test fun binRequiresPositiveCartridgeHeader() {
        assertEquals(EmulationPlatform.DREAMCAST, SegaPlatformDetector.detectHeader("track.bin", ByteArray(32768)))
        assertEquals(EmulationPlatform.GENESIS, SegaPlatformDetector.detectHeader("game.bin", header(0x100, "SEGA MEGA DRIVE")))
        assertEquals(EmulationPlatform.SEGA_32X, SegaPlatformDetector.detectHeader("game.bin", header(0x100, "SEGA 32X")))
    }
    @Test fun copierHeadersAndWordSwappedGenesisRomsAreRecognized() {
        assertEquals(EmulationPlatform.GENESIS, SegaPlatformDetector.detectHeader("game.bin", header(0x300, "SEGA MEGA DRIVE")))
        val bytes = header(0x100, "SEGA GENESIS")
        for (index in 0 until bytes.lastIndex step 2) {
            val first = bytes[index]
            bytes[index] = bytes[index + 1]
            bytes[index + 1] = first
        }
        assertEquals(EmulationPlatform.GENESIS, SegaPlatformDetector.detectHeader("game.bin", bytes))
    }
    @Test fun inventedSecurityProgramDoesNotMisclassifyGenericGenesisHeader() {
        val bytes = header(0x100, "SEGA GENESIS")
        repeat(0x400) { bytes[0x400 + it] = (it * 37 + 11).toByte() }
        assertEquals(EmulationPlatform.GENESIS, SegaPlatformDetector.detectHeader("game.bin", bytes))
        assertEquals(EmulationPlatform.SEGA_32X, SegaPlatformDetector.detectHeader("game.32x", bytes))
    }
    @Test fun smsHeaderRegionDistinguishesGameGear() {
        val bytes = header(0x7ff0, "TMR SEGA").copyOf(32768)
        bytes[0x7fff] = 0x60
        assertEquals(EmulationPlatform.GAME_GEAR, SegaPlatformDetector.detectHeader("game.bin", bytes))
        bytes[0x7fff] = 0x40
        assertEquals(EmulationPlatform.MASTER_SYSTEM, SegaPlatformDetector.detectHeader("game.bin", bytes))
    }
    @Test fun discHeadersRequirePositiveSegaCdIdentityAndPreserveDreamcast() {
        assertEquals(EmulationPlatform.SEGA_CD, SegaPlatformDetector.detectHeader("game.chd", header(0, "SEGADISCSYSTEM")))
        assertEquals(EmulationPlatform.SEGA_CD, SegaPlatformDetector.detectHeader("track.bin", header(16, "SEGADISCSYSTEM")))
        assertEquals(EmulationPlatform.DREAMCAST, SegaPlatformDetector.detectHeader("game.chd", byteArrayOf()))
        assertEquals(EmulationPlatform.DREAMCAST, SegaPlatformDetector.detectHeader("game.cue", header(0, "SEGA SEGAKATANA")))
        assertEquals(EmulationPlatform.DREAMCAST, SegaPlatformDetector.detectHeader("game.gdi", header(0, "SEGADISCSYSTEM")))
    }
    @Test fun cueReadsReferencedTrackAndPlaylistReadsDisc() = tempDirectory { dir ->
        File(dir, "disc.bin").writeBytes(header(0, "SEGADISCSYSTEM"))
        val cue = File(dir, "disc.cue").apply { writeText("FILE \"disc.bin\" BINARY\n TRACK 01 MODE1/2048\n INDEX 01 00:00:00") }
        assertEquals(EmulationPlatform.SEGA_CD, SegaPlatformDetector.detectFile(cue))
        val m3u = File(dir, "game.m3u").apply { writeText("#EXTM3U\ndisc.cue\n") }
        assertEquals(EmulationPlatform.SEGA_CD, SegaPlatformDetector.detectFile(m3u))
    }
    @Test fun zipRequiresSingleCartridgeAndLeavesArcadeArchivesAlone() = tempDirectory { dir ->
        val zip = File(dir, "game.zip")
        fun write(vararg names: String) { ZipOutputStream(zip.outputStream()).use { stream ->
            names.forEach { name -> stream.putNextEntry(ZipEntry(name)); stream.write(header(0x100, "SEGA GENESIS")); stream.closeEntry() }
        } }
        write("nested/game.bin", "README.txt")
        assertEquals(EmulationPlatform.GENESIS, SegaPlatformDetector.detectFile(zip))
        write("a.bin", "b.bin")
        assertEquals(EmulationPlatform.DREAMCAST, SegaPlatformDetector.detectFile(zip))
        write("rom.ic1")
        assertEquals(EmulationPlatform.DREAMCAST, SegaPlatformDetector.detectFile(zip))
    }
    private fun header(offset: Int, value: String) = ByteArray(maxOf(32768, offset + value.length)).apply {
        value.toByteArray(Charsets.US_ASCII).copyInto(this, offset)
    }
    private fun tempDirectory(block: (File) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("sega-detector").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
}
