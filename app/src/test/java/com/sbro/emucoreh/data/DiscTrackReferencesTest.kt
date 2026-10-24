package com.sbro.emucoreh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscTrackReferencesTest {
    @Test
    fun gdiTracksAreParsedFromEverySupportedLineFormat() {
        val gdi = """
            4
            1 0 4 2048 track01.iso 0
            2 450 0 2352 "track 02.raw" 0
            3 45000 4 2048 tracks/track03.iso 0
            # comment
            4 450000 4 2048 TRACK04.ISO 0
        """.trimIndent()

        assertEquals(
            listOf("track01.iso", "track 02.raw", "track03.iso", "TRACK04.ISO"),
            DiscSizeResolver.trackReferences("disc.gdi", gdi)
        )
    }

    @Test
    fun cueAndGdiDescriptorsHideOnlyTheirReferencedTracks() {
        val tracks = DiscTrackReferences.referenced(
            sequenceOf(
                "disc.gdi" to "1 0 4 2048 track01.iso 0\n3 45000 4 2048 track03.iso 0",
                "other.cue" to "FILE \"game.bin\" BINARY\n  TRACK 01 MODE1/2352\n",
                "unquoted.cue" to "FILE plain.iso BINARY\n  TRACK 01 MODE1/2048\n",
            )
        )

        assertTrue(DiscTrackReferences.isReferenced("track01.iso", tracks))
        assertTrue(DiscTrackReferences.isReferenced("TRACK03.ISO", tracks))
        assertTrue(DiscTrackReferences.isReferenced("game.BIN", tracks))
        assertTrue(DiscTrackReferences.isReferenced("plain.iso", tracks))
        assertFalse(DiscTrackReferences.isReferenced("track02.raw", tracks))
        assertFalse(DiscTrackReferences.isReferenced("standalone.iso", tracks))
        assertFalse(DiscTrackReferences.isReferenced("disc.gdi", tracks))
    }

    @Test
    fun aTrackNameOnItsOwnStaysALibraryEntryWhenNoDescriptorReferencesIt() {
        assertTrue(DiscTrackReferences.isTrackCandidate("track01.iso"))
        assertTrue(DiscTrackReferences.isTrackCandidate("track01.bin"))
        assertFalse(DiscTrackReferences.isTrackCandidate("game.chd"))
        assertFalse(DiscTrackReferences.isReferenced("track01.iso", emptySet()))
    }
}
