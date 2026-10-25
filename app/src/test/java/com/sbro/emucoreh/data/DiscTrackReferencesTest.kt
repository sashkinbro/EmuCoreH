package com.sbro.emucoreh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class DiscTrackReferencesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun authoredLocalTreeKeepsStandaloneGamesAndExcludesNestedAndParentTracks() {
        val root = temporaryFolder.newFolder("library")
        val contents = mapOf(
            "Dreamcast/Game/disc.gdi" to "1 0 4 2048 tracks/track01.iso 0",
            "Dreamcast/Game/tracks/track01.iso" to "track data",
            "Dreamcast/Game/track01.iso" to "standalone game",
            "Sega CD/discs/game.cue" to "FILE \"../data.bin\" BINARY",
            "Sega CD/data.bin" to "track data",
            "Mega Drive/standalone.bin" to "cartridge data"
        )
        val files = contents.map { (path, text) ->
            root.resolve(path).apply { requireNotNull(parentFile).mkdirs(); writeText(text) }
        }
        fun relative(file: java.io.File) = root.toPath().relativize(file.toPath()).joinToString("/")
        val references = DiscTrackReferences.referenced(files.asSequence()
            .filter { DiscTrackReferences.isDescriptor(it.name) }
            .map { relative(it) to it.readText() })

        assertEquals(listOf("Dreamcast/Game/disc.gdi", "Dreamcast/Game/track01.iso",
            "Sega CD/discs/game.cue", "Mega Drive/standalone.bin"),
            DiscTrackReferences.withoutReferencedTracks(files.reversed(), references, ::relative)
                .reversed().map(::relative))
    }

    @Test
    fun filteringAWholeTreeIsIndependentOfTraversalOrderAndKeepsAmbiguousPaths() {
        val tracks = DiscTrackReferences.referenced(sequenceOf(
            "Game/disc.gdi" to "1 0 4 2048 tracks/track01.iso 0",
            "Another/game.cue" to "FILE \"../shared.bin\" BINARY"
        ))
        val entries = listOf(
            "Game/tracks/track01.iso", "shared.bin", "Game/track01.iso",
            "Game/disc.gdi", "Another/game.cue", "SHARED.bin"
        )

        assertEquals(listOf("shared.bin", "Game/track01.iso", "Game/disc.gdi", "Another/game.cue", "SHARED.bin"),
            DiscTrackReferences.withoutReferencedTracks(entries, tracks) { it })
    }

    @Test
    fun descriptorsResolveTracksInTheirOwnDirectoryAndWithinTheScannedRoot() {
        val tracks = DiscTrackReferences.referenced(sequenceOf(
            "Dreamcast/Game/disc.gdi" to "1 0 4 2048 tracks/track01.iso 0",
            "Sega CD/Discs/game.cue" to "FILE \"../Audio/track.bin\" BINARY",
            "disc.cue" to "FILE \"../outside.iso\" BINARY\nFILE \"C:/outside.bin\" BINARY"
        ))

        assertEquals(setOf("dreamcast/game/tracks/track01.iso", "sega cd/audio/track.bin"), tracks)
        assertFalse(DiscTrackReferences.isReferenced("Dreamcast/Game/track01.iso", tracks))
        assertTrue(DiscTrackReferences.isReferenced("Dreamcast/Game/tracks/track01.iso", tracks))
    }

    @Test
    fun nestedTrackReferenceDoesNotHideAnUnrelatedSiblingWithTheSameName() {
        val tracks = DiscTrackReferences.referenced(sequenceOf(
            "disc.cue" to "FILE \"tracks/game.iso\" BINARY"
        ))

        assertFalse(DiscTrackReferences.isReferenced("game.iso", tracks))
        assertTrue(DiscTrackReferences.isReferenced("tracks/game.iso", tracks))
    }

    @Test
    fun cueCommentsDoNotHideStandaloneGames() {
        val tracks = DiscTrackReferences.referenced(sequenceOf(
            "disc.cue" to "REM FILE \"standalone.iso\" BINARY\nFILE \"real.bin\" BINARY"
        ))

        assertFalse(DiscTrackReferences.isReferenced("standalone.iso", tracks))
        assertTrue(DiscTrackReferences.isReferenced("real.bin", tracks))
    }

    @Test
    fun cueByteOrderMarkDoesNotHideTheFirstFileDirective() {
        val tracks = DiscTrackReferences.referenced(sequenceOf(
            "disc.cue" to "\uFEFFFILE \"real.bin\" BINARY"
        ))
        assertTrue(DiscTrackReferences.isReferenced("real.bin", tracks))
    }

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
            listOf("track01.iso", "track 02.raw", "tracks/track03.iso", "TRACK04.ISO"),
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
