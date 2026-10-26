package com.sbro.emucoreh.data.catalog

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaturnCoverFilenameRegressionTest {
    private val titles = JSONObject().put("panzer dragoon", "saturn/1667.jpg")
        .put("d", "saturn/4319.jpg").put("z", "saturn/3275.jpg")

    @Test fun verifiedPhysicalPressingTagsAndSingleCharacterNamesResolve() {
        for (tag in listOf("5S", "2M", "21", "1M10")) {
            assertEquals("saturn/1667.jpg", SegaCoverTitleMatcher.resolve(titles, null, "Panzer Dragoon (USA) ($tag).chd"))
        }
        assertEquals("saturn/4319.jpg", SegaCoverTitleMatcher.resolve(titles, null, "D (USA) (Disc 1).chd"))
        assertEquals("saturn/3275.jpg", SegaCoverTitleMatcher.resolve(titles, null, "Z (Japan).cue"))
    }

    @Test fun physicalTagsNeverDiscardUnknownEditionOrSequelIdentity() {
        for (name in listOf("Panzer Dragoon (Demo) (USA) (5S).chd", "Panzer Dragoon (2) (USA).chd",
            "Panzer Dragoon (Special Edition) (Japan) (21).chd", "Panzer Dragoon II (USA) (5S).chd", "D 2 (USA).chd")) {
            assertNull(name, SegaCoverTitleMatcher.resolve(titles, null, name))
        }
    }
}
