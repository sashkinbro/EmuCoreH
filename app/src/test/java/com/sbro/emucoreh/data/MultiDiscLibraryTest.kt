package com.sbro.emucoreh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiDiscLibraryTest {
    @Test fun playlistInheritsSegaCdPlatform() {
        val first = game("First.cue").copy(platform = "SEGA_CD")
        val second = game("Second.cue").copy(platform = "SEGA_CD")
        val playlist = game("Game.m3u")
        val grouped = MultiDiscLibrary.group(listOf(first, second, playlist),
            mapOf(playlist.path to listOf(first.path, second.path))).single()
        assertEquals("SEGA_CD", grouped.platform)
        assertEquals("SEGA_CD", grouped.discs.first().asGameItem(grouped).platform)
    }

    @Test fun mixedConsoleDiscsCannotFormAPlaylistOrAutomaticGroup() {
        val first = game("Adventure (Disc 1).chd").copy(platform = "SEGA_CD")
        val second = game("Adventure (Disc 2).chd").copy(platform = "DREAMCAST")
        val playlist = game("Adventure.m3u")
        assertEquals(listOf(first, second), MultiDiscLibrary.group(listOf(first, second)))
        val games = listOf(first, second, playlist)
        assertEquals(games, MultiDiscLibrary.group(games, mapOf(playlist.path to listOf(first.path, second.path))))
    }

    private fun game(name: String, parent: String = "/games", serial: String? = null) = GameItem(
        title = name.substringBeforeLast('.'), path = "$parent/$name", fileName = name,
        fileSize = 100L, lastModified = 10L, serial = serial, discSize = 200L
    )

    @Test
    fun numberedSiblingsBecomeOneCardInNumericOrder() {
        val second = game("Adventure (USA) (Disc 2).chd", serial = "T-2222")
        val first = game("Adventure (USA) (Disc 1).chd", serial = "T-1111")

        val grouped = MultiDiscLibrary.group(listOf(second, first)).single()

        assertEquals(first.path, grouped.path)
        assertEquals("Adventure (USA)", grouped.title)
        assertEquals(listOf(first.path, second.path), grouped.discs.map { it.path })
        assertEquals(listOf(1, 2), grouped.discs.map { it.number })
        assertEquals(listOf("T-1111", "T-2222"), grouped.discs.map { it.serial })
        assertEquals(400L, grouped.discSize)
    }

    @Test
    fun differentRegionsRevisionsAndFormatsStaySeparate() {
        val games = listOf(
            game("Adventure (USA) (Disc 1).chd"), game("Adventure (Europe) (Disc 2).chd"),
            game("Adventure (USA) (Rev 1) (Disc 2).chd"), game("Adventure (USA) (Disc 2).gdi")
        )

        assertEquals(games, MultiDiscLibrary.group(games))
    }

    @Test
    fun differentParentsAndUnnumberedTitlesStaySeparate() {
        val games = listOf(
            game("Adventure (Disc 1).chd", "/games/a"), game("Adventure (Disc 2).chd", "/games/b"),
            game("Adventure.chd"), game("Adventure 2.chd")
        )

        assertEquals(games, MultiDiscLibrary.group(games))
    }

    @Test
    fun duplicateDiscNumbersPreventHeuristicGrouping() {
        val games = listOf(
            game("Adventure (Disc 1).chd"), game("Adventure (Disk 1).chd"),
            game("Adventure (Disc 2).chd")
        )

        assertEquals(games, MultiDiscLibrary.group(games))
    }

    @Test
    fun playlistOrderOverridesFilenameNumberAndSuppressesItsMembers() {
        val first = game("Adventure (Disc 1).chd")
        val second = game("Adventure (Disc 2).chd", "/games/subfolder")
        val playlist = game("Adventure.m3u")
        val other = game("Other.chd")

        val grouped = MultiDiscLibrary.group(
            listOf(first, second, playlist, other),
            playlistMembers = mapOf(playlist.path to listOf(second.path, first.path))
        )

        assertEquals(listOf(playlist.path, other.path), grouped.map { it.path })
        assertEquals(listOf(second.path, first.path), grouped.first().discs.map { it.path })
        assertEquals(listOf(1, 2), grouped.first().discs.map { it.number })
    }

    @Test
    fun missingOrRepeatedPlaylistReferencesDoNotHidePhysicalDiscs() {
        val first = game("First.chd")
        val second = game("Second.chd")
        val playlist = game("Adventure.m3u")
        val games = listOf(first, second, playlist)

        assertEquals(games, MultiDiscLibrary.group(games, mapOf(playlist.path to listOf(first.path, "/missing.chd"))))
        assertEquals(games, MultiDiscLibrary.group(games, mapOf(playlist.path to listOf(first.path, first.path))))
    }

    @Test
    fun playlistParsingKeepsRelativeDirectoriesAndIgnoresComments() {
        assertEquals(
            listOf("disc1/Adventure.gdi", "../disc2/Adventure.chd"),
            MultiDiscLibrary.parsePlaylist("\uFEFF#EXTM3U\n#EXTINF:0,Disc 1\n\"disc1/Adventure.gdi\"\n\n../disc2/Adventure.chd")
        )
    }

    @Test
    fun safReferenceResolutionStaysInsideTheScannedRoot() {
        assertEquals(
            listOf("Adventure", "disc2", "Game.chd"),
            MultiDiscLibrary.resolveRelativeSegments(listOf("Adventure", "playlists"), "../disc2/Game.chd")
        )
        assertEquals(null, MultiDiscLibrary.resolveRelativeSegments(emptyList(), "../outside.chd"))
        assertEquals(null, MultiDiscLibrary.resolveRelativeSegments(emptyList(), "content://provider/unscanned"))
        assertEquals(null, MultiDiscLibrary.resolveRelativeSegments(emptyList(), "/absolute.chd"))
    }

    @Test
    fun opaqueSafUrisOnlyGroupUsingActualParentIdentity() {
        val first = game("Adventure (Disc 1).chd").copy(path = "content://provider/document/opaque1")
        val second = game("Adventure (Disc 2).chd").copy(path = "content://provider/document/opaque2")
        val games = listOf(first, second)

        assertEquals(games, MultiDiscLibrary.group(games))
        assertEquals(1, MultiDiscLibrary.group(games, parentIdentities = games.associate { it.path to "actual-parent" }).size)
        assertEquals(games, MultiDiscLibrary.group(games, parentIdentities = mapOf(first.path to "parent-a", second.path to "parent-b")))
    }

    @Test
    fun selectingADiscKeepsGroupArtworkAndUsesPhysicalMetadata() {
        val first = game("Adventure (Disc 1).chd", serial = "T-1111")
        val second = game("Adventure (Disc 2).chd", serial = "T-2222").copy(fileSize = 456, lastModified = 789)
        val grouped = MultiDiscLibrary.group(listOf(first, second)).single().copy(coverArtPath = "/cover.png")

        val selected = grouped.launchDiscs()[1].asGameItem(grouped)

        assertEquals(grouped.title, selected.title)
        assertEquals(grouped.coverArtPath, selected.coverArtPath)
        assertEquals(second.path, selected.path)
        assertEquals(second.fileName, selected.fileName)
        assertEquals(second.serial, selected.serial)
        assertEquals(second.fileSize, selected.fileSize)
        assertEquals(second.lastModified, selected.lastModified)
        assertTrue(selected.discs.isEmpty())
    }

    @Test
    fun singleDiscGamesKeepTheirOriginalLaunchPath() {
        val single = game("Single.chd")

        assertEquals(single.path, single.launchDiscs().single().path)
        assertEquals(single, single.launchDiscs().single().asGameItem(single))
    }

    @Test
    fun overlappingScanRootsCannotRestoreAHiddenMemberCard() {
        val first = game("Adventure (Disc 1).chd")
        val second = game("Adventure (Disc 2).chd")
        val group = MultiDiscLibrary.group(listOf(first, second)).single()

        assertEquals(listOf(group), MultiDiscLibrary.group(listOf(group, second)))
        assertEquals(listOf(group), MultiDiscLibrary.group(listOf(group)))
    }

    @Test
    fun anExplicitPlaylistWinsOverAFilenameGroupFromAnotherScanRoot() {
        val first = game("Adventure (Disc 1).chd")
        val second = game("Adventure (Disc 2).chd")
        val playlist = game("Adventure.m3u")
        val numbered = MultiDiscLibrary.group(listOf(first, second)).single()
        val explicit = MultiDiscLibrary.group(
            listOf(first, second, playlist), mapOf(playlist.path to listOf(first.path, second.path))
        ).single()

        assertEquals(listOf(explicit), MultiDiscLibrary.group(listOf(numbered, explicit)))
    }

    @Test
    fun overlappingSafTreeGrantsDoNotRestoreTheSamePhysicalDisc() {
        val authority = "content://com.android.externalstorage.documents"
        val first = game("Disc1.chd").copy(path = "$authority/tree/primary%3AGames/document/primary%3AGames%2FDisc1.chd")
        val second = game("Disc2.chd").copy(path = "$authority/tree/primary%3AGames/document/primary%3AGames%2FDisc2%2FDisc2.chd")
        val alias = second.copy(path = "$authority/tree/primary%3AGames%2FDisc2/document/primary%3AGames%2FDisc2%2FDisc2.chd")
        val playlist = game("Game.m3u").copy(discs = listOf(
            first.launchDiscs().single(), second.launchDiscs().single().copy(number = 2)
        ))
        val documentIdentity: (String) -> String = { it.substringAfter("/document/", it) }

        assertEquals(listOf(playlist), MultiDiscLibrary.group(listOf(playlist, alias), identity = documentIdentity))
    }
}
