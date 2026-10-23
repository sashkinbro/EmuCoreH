package com.sbro.emucoreh.data.catalog

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SegaCoverTitleMatcherTest {
    private val titles = JSONObject().put("the black onyx", "sg_1000/6099.jpg")
        .put("ninja princess", "sg_1000/6119.jpg").put("ultimate mortal kombat 3", "genesis/1621.jpg")
        .put("castle of illusion starring mickey mouse", "game_gear/13464.jpg")
        .put("gg aleste 3", "game_gear/153283.jpg").put("golden axe ii", "genesis/16214.jpg")
        .put("the amazing spider man web of fire", "32x/45882.jpg")

    @Test fun filenameCanResolveWhenTheRomHeaderHasAnAbbreviatedTitle() {
        assertEquals("genesis/1621.jpg", SegaCoverTitleMatcher.resolve(titles, "ULTIMATE MK3", "Ultimate Mortal Kombat 3 (USA).md"))
    }

    @Test fun articleBeforeSubtitleIsMovedWithoutDroppingTheSubtitle() {
        assertEquals("32x/45882.jpg", SegaCoverTitleMatcher.resolve(titles, "SPIDER-MAN",
            "Amazing Spider-Man, The - Web of Fire (USA).32x"))
    }

    @Test fun noIntroExtensionsTranslationFlagsAndTrailingArticlesResolveExactly() {
        assertEquals("sg_1000/6099.jpg", SegaCoverTitleMatcher.resolve(titles, null,
            "Black Onyx, The (Japan) [T-En by SSTranslations v1.0] [Enhanced].sg"))
        assertEquals("sg_1000/6119.jpg", SegaCoverTitleMatcher.resolve(titles, null,
            "Ninja Princess (Japan) [T-En by Goldenband v1.0].sg"))
        assertEquals("game_gear/13464.jpg", SegaCoverTitleMatcher.resolve(titles, null,
            "Castle of Illusion Starring Mickey Mouse (J) [S][h1].gg"))
        assertEquals("game_gear/153283.jpg", SegaCoverTitleMatcher.resolve(titles, null,
            "GG Aleste 3 (Japan) (En) (Aleste Collection).gg"))
    }

    @Test fun missingGameNeverBorrowsASequelOrDifferentGameWithSimilarWords() {
        assertNull(SegaCoverTitleMatcher.resolve(titles, null, "Golden Axe (World).md"))
        assertNull(SegaCoverTitleMatcher.resolve(titles, null, "GG Aleste 2 (Japan).gg"))
        assertNull(SegaCoverTitleMatcher.resolve(JSONObject().put("dragon the bruce lee story", "master_system/398885.jpg"),
            null, "Bruce Lee (World) (Aftermarket) (Unl).sms"))
    }
}
