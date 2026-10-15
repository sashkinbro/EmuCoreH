package com.sbro.emucoreh.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject
import java.io.File

class CoverIndexRepositoryTest {

    @Test fun discAndRegionTagsCanAppearInEitherOrderWithoutDiscardingEditions() {
        val titles = JSONObject().put("shenmue", "covers/base.jpg")
            .put("shenmue special edition", "covers/special.jpg")
        for (suffix in listOf("(USA) (Disc 1)", "(Disc 1) (USA)", "(USA) [Disc 1 of 2]")) {
            assertEquals(suffix, "covers/base.jpg", CoverIndexRepository.resolveTitlePath(
                titles, "Shenmue $suffix.chd", allowFuzzy = false
            ))
            assertEquals(suffix, "covers/special.jpg", CoverIndexRepository.resolveTitlePath(
                titles, "Shenmue (Special Edition) $suffix.chd", allowFuzzy = false
            ))
        }
        assertEquals("covers/base.jpg", CoverIndexRepository.resolveTitlePath(
            titles, "Shenmue v1.000 (Disc 1).chd", allowFuzzy = false
        ))
        assertEquals("covers/31200.jpg", CoverIndexRepository.resolveTitlePath(
            bundledTitles(), "DUX (v1.5) v1.000 (Europe).cdi", allowFuzzy = false
        ))
    }

    @Test fun trademarksAndWidthVariantsDoNotBecomeExtraTitleLetters() {
        assertEquals("covers/94999.jpg", CoverIndexRepository.resolveTitlePath(
            bundledTitles(), "Ｒｅｚ™ (USA).chd", allowFuzzy = false
        ))
    }

    @Test fun parenthesizedNamedVersionsSurviveDumpMetadataCleanup() {
        assertEquals("covers/131822.jpg", CoverIndexRepository.resolveTitlePath(
            bundledTitles(), "Phantasy Star Online (Ver. 2) (Japan) [!].chd", allowFuzzy = false
        ))
        assertEquals("covers/14989.jpg", CoverIndexRepository.resolveTitlePath(
            bundledTitles(), "Border Down (Limited Edition) (Japan).chd", allowFuzzy = false
        ))
    }

    @Test fun dottedNamedEditionIsTriedBeforeTreatingTheVersionAsADiscRevision() {
        assertEquals("covers/31200.jpg", CoverIndexRepository.resolveTitlePath(
            bundledTitles(), "DUX v1.5 (Europe).cdi", allowFuzzy = false
        ))
        assertEquals("covers/31200.jpg", CoverIndexRepository.resolveTitlePath(
            bundledTitles(), "DUX (v1.5) (Europe).cdi", allowFuzzy = false
        ))
    }

    @Test fun meaningfulSubtitlesAreNotMistakenForCrackedDumpFlags() {
        val titles = JSONObject().put("some game", "covers/base.jpg")
            .put("some game crazy mode", "covers/mode.jpg")
        for (name in listOf("Some Game (Crazy Mode) (USA).chd", "Some Game [Crazy Mode] (USA).chd")) {
            assertEquals(name, "covers/mode.jpg", CoverIndexRepository.resolveTitlePath(titles, name, allowFuzzy = false))
        }
    }

    @Test fun allCatalogTitlesKeepTheirExplicitArtworkThroughStandardDumpSuffixes() {
        val titles = bundledTitles()
        for (key in titles.keys()) {
            assertEquals(key, titles.getString(key), CoverIndexRepository.resolveTitlePath(
                titles, "$key v1.000 (1999)(Example Publisher)(US)[!][4S].CHD", allowFuzzy = false
            ))
        }
    }

    @Test fun articlePositionAndOmissionResolveAnUnambiguousCatalogTitle() {
        for (name in listOf("House of the Dead 2, The (USA).chd", "House of the Dead 2")) {
            assertEquals(name, "covers/112307.jpg", CoverIndexRepository.resolveTitlePath(
                bundledTitles(), name, allowFuzzy = false
            ))
        }
    }

    @Test fun underscoreDumpSeparatorsAndPublisherTagsDoNotHideTheTitle() {
        assertEquals("covers/125209.jpg", CoverIndexRepository.resolveTitlePath(
            bundledTitles(), "SEGA_RALLY_2_v1_000_(1999)(Sega)(US)[!][4S].CHD", allowFuzzy = false
        ))
        assertEquals("covers/112301.jpg", CoverIndexRepository.resolveTitlePath(
            bundledTitles(), "Chu Chu Rocket! (Europe) [T+Eng].GDI", allowFuzzy = false
        ))
    }

    @Test fun unknownEditionQualifiersAreNeverDiscardedToForceAFastMatch() {
        val titles = JSONObject().put("a game", "covers/base.jpg")
        assertNull(CoverIndexRepository.resolveTitlePath(titles, "A Game (Unknown Edition) (USA).chd", allowFuzzy = false))
    }

    @Test fun twoCharacterTitlesCanMatchExactlyWithoutEnablingShortFuzzyQueries() {
        val titles = JSONObject().put("d2", "covers/8117.jpg").put("es", "covers/115954.jpg")
            .put("rez", "covers/94999.jpg")
        assertEquals("covers/8117.jpg", CoverIndexRepository.resolveTitlePath(titles, "D2 (USA).chd", allowFuzzy = false))
        assertEquals("covers/115954.jpg", CoverIndexRepository.resolveTitlePath(titles, "Es (Japan).chd", allowFuzzy = false))
        assertNull(CoverIndexRepository.resolveTitlePath(titles, "Re"))
    }

    @Test fun fallbackPrefersTheSpecificSequelInsteadOfTheShortestParentTitle() {
        val titles = JSONObject().put("sonic adventure", "covers/first.jpg")
            .put("sonic adventure 2", "covers/second.jpg")
        assertEquals("covers/second.jpg", CoverIndexRepository.resolveTitlePath(titles, "Sonic Adventure 2 Preview"))
        assertNull(CoverIndexRepository.resolveTitlePath(titles, "Sonic Adventure 3"))
    }

    @Test fun equallyPlausibleFallbacksRemainUnresolved() {
        val titles = JSONObject().put("super game alpha", "covers/first.jpg")
            .put("super game beta", "covers/second.jpg")
        assertNull(CoverIndexRepository.resolveTitlePath(titles, "Super Game"))
    }

    @Test fun fallbackDoesNotDiscardNamedEditionOrVersionTokens() {
        val titles = JSONObject().put("capcom vs snk", "covers/base.jpg")
            .put("phantasy star online", "covers/online.jpg")
        assertNull(CoverIndexRepository.resolveTitlePath(titles, "Capcom vs SNK Kanzenban"))
        assertNull(CoverIndexRepository.resolveTitlePath(titles, "Phantasy Star Online Ver. 2"))
    }

    @Test fun fastLookupDoesNotFinalizeASequelUsingItsParentsFuzzyCover() {
        val titles = JSONObject().put("sonic adventure", "covers/first.jpg")
            .put("sonic adventure 2", "covers/second.jpg")
        assertNull(CoverIndexRepository.resolveTitlePath(titles, "Sonic Adventure 2 Preview", allowFuzzy = false))
    }

    @Test fun fastLookupStillResolvesKnownDiscRevisionsAndSpacingAliases() {
        assertEquals("covers/103173.jpg", CoverIndexRepository.resolveTitlePath(
            bundledTitles(), "Soul Calibur v1.000 (1999)(Namco)(US)[!][4S].chd", allowFuzzy = false
        ))
    }

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
