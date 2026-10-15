package com.sbro.emucoreh.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject
import java.io.File

class CoverIndexRepositoryTest {

    private fun bundledTitles(): JSONObject {
        val asset = listOf(
            File("src/main/assets/catalog/covers_index.json"),
            File("app/src/main/assets/catalog/covers_index.json")
        ).first { it.isFile }
        return JSONObject(asset.readText()).getJSONObject("titles")
    }

    @Test
    fun screenshotSoulCaliburRevisionResolvesBundledArtwork() {
        assertEquals("covers/103173.jpg", CoverIndexRepository.resolveTitlePath(
            bundledTitles(), "Soul Calibur v1.000"
        ))
    }

    @Test
    fun soulCaliburDumpFilenameResolvesBundledArtwork() {
        assertEquals("covers/103173.jpg", CoverIndexRepository.resolveTitlePath(
            bundledTitles(), "Soul Calibur v1.000 (1999)(Namco)(US)[!][4S].chd"
        ))
    }

    @Test
    fun revisionLookupPreservesSequelNumbers() {
        val titles = bundledTitles()
        // The title alias selects this catalogued Japanese edition; the US
        // cover (2412) is selected only by its own product code MK-51136.
        assertEquals("covers/125201.jpg", CoverIndexRepository.resolveTitlePath(titles, "Crazy Taxi 2 v1.000"))
        assertNull(CoverIndexRepository.resolveTitlePath(titles, "Soul Calibur 2 v1.000"))
    }

    @Test
    fun exactCatalogTitleWinsBeforeSpacingAliases() {
        val titles = JSONObject().put("soul calibur", "covers/exact.jpg")
            .put("soulcalibur", "covers/other.jpg")
        assertEquals("covers/exact.jpg", CoverIndexRepository.resolveTitlePath(titles, "Soul Calibur"))
    }

    @Test
    fun revisionStillPrefersAnExactSpellingWhenBundledSpacingAliasesConflict() {
        assertEquals("covers/137280.jpg", CoverIndexRepository.resolveTitlePath(bundledTitles(), "MDK 2 v1.000"))
    }

    @Test
    fun namedVersionIsPreservedAsPartOfTheCatalogTitle() {
        assertEquals("covers/131822.jpg", CoverIndexRepository.resolveTitlePath(
            bundledTitles(), "Phantasy Star Online Ver. 2"
        ))
    }

    @Test
    fun resolvedSoulCaliburArtworkHasTheVerifiedFlatAndThemeVariants() {
        val path = CoverIndexRepository.resolveTitlePath(bundledTitles(), "Soul Calibur v1.000")!!
        assertEquals("https://raw.githubusercontent.com/sashkinbro/EmuCoreH-Covers/main/covers/103173.jpg",
            CoverIndexRepository.makeCoverUrl(path, com.sbro.emucoreh.data.AppPreferences.COVER_ART_STYLE_FLAT))
        assertEquals("https://raw.githubusercontent.com/sashkinbro/EmuCoreH-Covers/main/covers/3d/103173.webp",
            CoverIndexRepository.makeCoverUrl(path, com.sbro.emucoreh.data.AppPreferences.COVER_ART_STYLE_3D))
        assertEquals("https://raw.githubusercontent.com/sashkinbro/EmuCoreH-Covers/main/covers/3d/light/103173.webp",
            CoverIndexRepository.makeCoverUrl(path, com.sbro.emucoreh.data.AppPreferences.COVER_ART_STYLE_3D, isLight = true))
    }

    @Test
    fun conflictingSpacingAliasesDoNotChooseAnArbitraryGame() {
        val titles = JSONObject().put("ab cd", "covers/first.jpg").put("a bcd", "covers/second.jpg")
        assertNull(CoverIndexRepository.resolveTitlePath(titles, "ABC D v1.000"))
    }

    @Test
    fun normalizesSerialsCorrectly() {
        assertEquals("MK51037", CoverIndexRepository.normalizeSerial("MK-51037"))
        assertEquals("MK51037", CoverIndexRepository.normalizeSerial("mk-51037"))
        assertEquals("MK51037", CoverIndexRepository.normalizeSerial("MK51037"))
        assertEquals("T1202N", CoverIndexRepository.normalizeSerial("T-1202N"))
        assertEquals("HDR0190", CoverIndexRepository.normalizeSerial("HDR-0190"))
        assertEquals("T1202N", CoverIndexRepository.normalizeSerial(" t-1202n "))
    }

    @Test
    fun rejectsInvalidSerials() {
        assertNull(CoverIndexRepository.normalizeSerial(null))
        assertNull(CoverIndexRepository.normalizeSerial(""))
        assertNull(CoverIndexRepository.normalizeSerial("   "))
        assertNull(CoverIndexRepository.normalizeSerial("ABC")) // less than 4 chars
    }

    @Test
    fun normalizesTitlesCorrectly() {
        assertEquals("sonic adventure 2", CoverIndexRepository.normalizeTitle("Sonic Adventure 2"))
        assertEquals("sonic adventure", CoverIndexRepository.normalizeTitle("Sonic Adventure™"))
        assertEquals("crazy taxi 2", CoverIndexRepository.normalizeTitle("Crazy Taxi® 2"))
        assertEquals("capcom vs snk", CoverIndexRepository.normalizeTitle("Capcom vs. SNK"))
        assertEquals("marvel vs capcom", CoverIndexRepository.normalizeTitle("Marvel vs. Capcom!"))
    }

    @Test
    fun extractsRomsetKeys() {
        assertEquals("18wheelr", CoverIndexRepository.romsetKey("18wheelr.zip"))
        assertEquals("18wheelr", CoverIndexRepository.romsetKey("18WHEELR.ZIP"))
        assertEquals("cvs2gd", CoverIndexRepository.romsetKey("arcade/cvs2gd.7z"))
        assertEquals("ikaruga", CoverIndexRepository.romsetKey("C:\\roms\\ikaruga.zip"))
        assertEquals("mslug6", CoverIndexRepository.romsetKey("mslug6"))
    }

    @Test
    fun rejectsInvalidRomsetNames() {
        assertNull(CoverIndexRepository.romsetKey(null))
        assertNull(CoverIndexRepository.romsetKey(""))
        assertNull(CoverIndexRepository.romsetKey("   "))
    }

    @Test
    fun repositoryBaseUrlPointsToEmuCoreHCoversMain() {
        assertEquals(
            "https://raw.githubusercontent.com/sashkinbro/EmuCoreH-Covers/main",
            CoverIndexRepository.BASE_URL
        )
    }

    @Test
    fun makeCoverUrlTransforms3DAndFlatStyles() {
        val base = CoverIndexRepository.BASE_URL
        
        // 3D Dark Dreamcast
        assertEquals(
            "$base/covers/3d/420.webp",
            CoverIndexRepository.makeCoverUrl("covers/420.jpg", style = com.sbro.emucoreh.data.AppPreferences.COVER_ART_STYLE_3D, isLight = false)
        )
        // 3D Light Dreamcast
        assertEquals(
            "$base/covers/3d/light/420.webp",
            CoverIndexRepository.makeCoverUrl("covers/420.jpg", style = com.sbro.emucoreh.data.AppPreferences.COVER_ART_STYLE_3D, isLight = true)
        )
        // Flat Dreamcast
        assertEquals(
            "$base/covers/420.jpg",
            CoverIndexRepository.makeCoverUrl("covers/420.jpg", style = com.sbro.emucoreh.data.AppPreferences.COVER_ART_STYLE_FLAT, isLight = false)
        )
        // 3D Dark Arcade
        assertEquals(
            "$base/arcade/3d/18wheelr.webp",
            CoverIndexRepository.makeCoverUrl("arcade/18wheelr.jpg", style = com.sbro.emucoreh.data.AppPreferences.COVER_ART_STYLE_3D, isLight = false)
        )
        // 3D Light Arcade
        assertEquals(
            "$base/arcade/3d/light/18wheelr.webp",
            CoverIndexRepository.makeCoverUrl("arcade/18wheelr.jpg", style = com.sbro.emucoreh.data.AppPreferences.COVER_ART_STYLE_3D, isLight = true)
        )
        // Flat Arcade
        assertEquals(
            "$base/arcade/18wheelr.jpg",
            CoverIndexRepository.makeCoverUrl("arcade/18wheelr.jpg", style = com.sbro.emucoreh.data.AppPreferences.COVER_ART_STYLE_FLAT, isLight = false)
        )
    }
}
