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
    @Test(expected = IllegalStateException::class) fun ambiguousPacksAreRejected() {
        resolveTexturePackLayout(setOf("A/textures.ini", "B/textures.ini"))
    }
    @Test(expected = IllegalStateException::class) fun missingIniIsRejected() {
        resolveTexturePackLayout(setOf("A.png"))
    }
}
