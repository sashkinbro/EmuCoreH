package com.sbro.emucoreh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TexturePackLayoutTest {
    @Test fun nestedAssetFoldersArePreserved() {
        val paths = setOf("pack-main/PSP/TEXTURES/ULES00151/textures.ini",
            "pack-main/PSP/TEXTURES/ULES00151/textures/ui/icon.png",
            "pack-main/PSP/TEXTURES/ULES00151/regions/eu.ini", "outside.png")
        assertEquals(listOf("textures.ini", "textures/ui/icon.png", "regions/eu.ini"),
            resolveTexturePackLayout(paths).values.toList())
    }
    @Test fun flatPackKeepsRelativePaths() {
        assertEquals(mapOf("textures.ini" to "textures.ini", "replacements/a.png" to "replacements/a.png"),
            resolveTexturePackLayout(setOf("textures.ini", "replacements/a.png")))
    }
    @Test fun mainIniIsCanonicalized() {
        assertEquals(mapOf("Pack/Textures.INI" to "textures.ini", "Pack/A.png" to "A.png"),
            resolveTexturePackLayout(setOf("Pack/Textures.INI", "Pack/A.png")))
    }
    @Test fun dreamcastPacksDropTheGameFolder() {
        assertEquals(mapOf("MK-51035/a.png" to "a.png", "MK-51035/tex/b.png" to "tex/b.png"),
            resolveTexturePackLayout(setOf("MK-51035/a.png", "MK-51035/tex/b.png")))
    }
    @Test(expected = IllegalArgumentException::class) fun ambiguousPacksAreRejected() {
        resolveTexturePackLayout(setOf("A/textures.ini", "B/textures.ini"))
    }
    @Test(expected = IllegalArgumentException::class) fun mixedGameFoldersAreRejected() {
        resolveTexturePackLayout(setOf("A/one.png", "B/two.png"))
    }
    @Test(expected = IllegalArgumentException::class) fun looseImagesAreRejected() {
        resolveTexturePackLayout(setOf("A.png"))
    }
}
