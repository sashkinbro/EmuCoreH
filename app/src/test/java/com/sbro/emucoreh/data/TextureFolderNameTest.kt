package com.sbro.emucoreh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextureFolderNameTest {

    @Test
    fun keepsDashedProductCodes() {
        assertEquals("MK-51035", textureFolderNameForGameId("MK-51035"))
        assertEquals("T-9706D", textureFolderNameForGameId("T-9706D"))
        assertEquals("HDR-0053", textureFolderNameForGameId("HDR-0053"))
    }

    @Test
    fun keepsRevisionSuffixedProductCodes() {
        assertEquals("MK-5103750", textureFolderNameForGameId("MK-5103750"))
        assertEquals("T-17702D-50", textureFolderNameForGameId("T-17702D-50"))
    }

    @Test
    fun keepsArcadeRomNames() {
        assertEquals("hotd2", textureFolderNameForGameId("hotd2"))
        assertEquals("mvsc2", textureFolderNameForGameId("mvsc2"))
    }

    @Test
    fun turnsSpacesIntoUnderscoresLikeTheCore() {
        assertEquals("DERBY_OWNERS_CLUB", textureFolderNameForGameId(" DERBY OWNERS CLUB "))
    }

    @Test
    fun stripsPathSeparatorsAndUnsafeCharacters() {
        assertEquals("a_b", textureFolderNameForGameId("a/b"))
        assertEquals("a_b", textureFolderNameForGameId("a\\b"))
        assertEquals("MK-51035_", textureFolderNameForGameId("MK-51035:"))
    }

    @Test
    fun rejectsEmptyAndRelativeNames() {
        assertNull(textureFolderNameForGameId(null))
        assertNull(textureFolderNameForGameId("   "))
        assertNull(textureFolderNameForGameId("."))
        assertNull(textureFolderNameForGameId(".."))
    }
}
