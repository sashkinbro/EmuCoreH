package com.sbro.emucoreh.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class JGenesisContentPreparerTest {
    @Test fun localPlaylistResolvesFirstPhysicalDisc() {
        val dir = java.nio.file.Files.createTempDirectory("sega-playlist").toFile()
        try {
            File(dir, "disc1.chd").writeText("disc1")
            File(dir, "disc2.chd").writeText("disc2")
            val playlist = File(dir, "Game.m3u").apply { writeText("#EXTM3U\ndisc1.chd\ndisc2.chd\n") }
            assertEquals(File(dir, "disc1.chd").canonicalPath, JGenesisContentPreparer.prepareFile(playlist))
        } finally { dir.deleteRecursively() }
    }
    @Test fun localPlaylistRejectsMissingMembersAndTraversal() {
        val dir = java.nio.file.Files.createTempDirectory("sega-playlist").toFile()
        try {
            val playlist = File(dir, "Game.m3u").apply { writeText("missing.chd") }
            assertTrue(runCatching { JGenesisContentPreparer.prepareFile(playlist) }.isFailure)
            playlist.writeText("../outside.chd")
            assertTrue(runCatching { JGenesisContentPreparer.prepareFile(playlist) }.isFailure)
            playlist.writeText("Game.m3u")
            assertTrue(runCatching { JGenesisContentPreparer.prepareFile(playlist) }.isFailure)
        } finally { dir.deleteRecursively() }
    }
    @Test fun cueReferencesIncludeAudioAndRepeatedReferencesOnlyOnce() {
        assertEquals(listOf("data.bin", "audio.wav"), JGenesisContentPreparer.cueReferences(
            "FILE \"data.bin\" BINARY\nFILE \"data.bin\" BINARY\nFILE \"audio.wav\" WAVE"))
    }
}
