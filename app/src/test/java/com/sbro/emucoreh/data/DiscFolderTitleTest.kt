package com.sbro.emucoreh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscFolderTitleTest {
    @Test
    fun genericDescriptorsUseTheGameFolderAsTheirTitle() {
        for (name in listOf("disc.gdi", "disc.cue", "game.cue", "cd.chd", "disc.iso")) {
            assertEquals(name, "King of Fighters NeoWave",
                GameRepository.genericDiscFolderTitle(name, "King of Fighters NeoWave"))
        }
        assertEquals("Metal Slug 6",
            GameRepository.genericDiscFolderTitle("track01.iso", "Metal Slug 6"))
    }

    @Test
    fun properlyNamedImagesAndPlatformFoldersAreNotRewritten() {
        assertNull(GameRepository.genericDiscFolderTitle("Sonic Adventure.gdi", "Sonic Adventure"))
        assertNull(GameRepository.genericDiscFolderTitle("disc.gdi", "Atomiswave"))
        assertNull(GameRepository.genericDiscFolderTitle("disc.gdi", "Emulator_Games"))
        assertNull(GameRepository.genericDiscFolderTitle("disc.gdi", "games"))
        assertNull(GameRepository.genericDiscFolderTitle("disc.gdi", null))
    }

    @Test
    fun onlyFileDerivedTitlesAreReplacedByTheFolderTitle() {
        assertTrue(GameRepository.shouldUseFolderTitle("disc.gdi", "disc.gdi"))
        assertTrue(GameRepository.shouldUseFolderTitle("disc", "disc.gdi"))
        assertTrue(GameRepository.shouldUseFolderTitle("", "game.cue"))
        assertFalse(GameRepository.shouldUseFolderTitle("Metal Slug 6", "disc.gdi"))
        assertFalse(GameRepository.shouldUseFolderTitle("King of Fighters XI", "disc.gdi"))
    }

    @Test
    fun folderPlatformHintsOnlyAffectDiscAndPlaylistContent() {
        assertEquals(LibraryPlatform.ATOMISWAVE,
            GameRepository.folderPlatformFor("disc.gdi", LibraryPlatform.ATOMISWAVE))
        assertEquals(LibraryPlatform.NAOMI,
            GameRepository.folderPlatformFor("game.chd", LibraryPlatform.NAOMI))
        assertEquals(LibraryPlatform.ATOMISWAVE,
            GameRepository.folderPlatformFor("Game.m3u", LibraryPlatform.ATOMISWAVE))
        assertNull(GameRepository.folderPlatformFor("mslug6.zip", LibraryPlatform.ATOMISWAVE))
        assertNull(GameRepository.folderPlatformFor("disc.gdi", null))
    }
}
