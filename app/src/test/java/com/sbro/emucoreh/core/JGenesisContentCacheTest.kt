package com.sbro.emucoreh.core

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class JGenesisContentCacheTest {
    @Test fun repeatedPreparationReusesFilesAndReleaseRemovesOnlyManagedContent() = withFixture { fixture ->
        val disc = fixture.sourceFile("disc.chd", "disc bytes")
        val other = fixture.sourceFile("disc2.chd", "second disc bytes")
        val prepared = fixture.cache.prepare(disc.path, fixture.source)
        val second = fixture.cache.prepare(other.path, fixture.source)
        assertEquals(prepared, fixture.cache.prepare(disc.path, fixture.source))
        assertEquals(1, fixture.opens.getValue(disc.path))
        assertTrue(prepared.isFile)
        assertTrue(second.isFile) // A swap must retain the previous image for rollback.
        val unrelated = File(fixture.cacheDirectory, "jgenesis-content/user-data").apply { mkdirs() }
        File(unrelated, "keep.txt").writeText("keep")
        fixture.cache.release()
        assertFalse(prepared.exists())
        assertFalse(second.exists())
        assertTrue(disc.isFile)
        assertTrue(File(unrelated, "keep.txt").isFile)
    }

    @Test fun playlistCopiesOnlyFirstDiscButChecksLaterCueTracks() = withFixture { fixture ->
        val first = fixture.sourceFile("disc1.chd", "first disc")
        fixture.sourceFile("track2.bin", "later disc track bytes")
        fixture.sourceFile("disc2.cue", "FILE \"track2.bin\" BINARY\n TRACK 01 MODE1/2352")
        val playlist = fixture.sourceFile("game.m3u", "disc1.chd\ndisc2.cue")
        val prepared = fixture.cache.prepare(playlist.path, fixture.source)
        assertEquals(first.readText(), prepared.readText())
        assertTrue(fixture.opens.containsKey(File(fixture.sourceDirectory, "track2.bin").path))
        assertEquals(1, prepared.parentFile!!.listFiles()!!.size)
        File(fixture.sourceDirectory, "track2.bin").delete()
        val badPlaylist = fixture.sourceFile("other.m3u", "disc1.chd\ndisc2.cue")
        assertTrue(runCatching { fixture.cache.prepare(badPlaylist.path, fixture.source) }.isFailure)
        assertTrue(prepared.isFile)
    }

    @Test fun sizeLimitAndFailedCopiesPreservePriorDiscWithoutPartialFiles() = withFixture(maxBytes = 16) { fixture ->
        val first = fixture.sourceFile("first.chd", "first")
        val prepared = fixture.cache.prepare(first.path, fixture.source)
        val large = fixture.sourceFile("large.chd", "x".repeat(32))
        assertTrue(runCatching { fixture.cache.prepare(large.path, fixture.source) }.isFailure)
        assertEquals("first", prepared.readText())
        assertEquals(listOf(prepared), prepared.parentFile!!.listFiles()!!.toList())
        fixture.cache.release()
        assertFalse(prepared.exists())
    }

    @Test fun abandonedCleanupSkipsActiveContentAndDoesNotFollowLinks() = withFixture { fixture ->
        val old = File(fixture.cacheDirectory, "jgenesis-content/00000000-0000-0000-0000-000000000001").apply { mkdirs() }
        File(old, "stale.bin").writeText("stale")
        val prepared = fixture.cache.prepare(fixture.sourceFile("disc.chd", "active").path, fixture.source)
        fixture.cache.cleanupAbandoned()
        assertFalse(old.exists())
        assertTrue(prepared.isFile)
        val outside = File(fixture.directory, "outside").apply { mkdirs() }
        val keep = File(outside, "keep").apply { writeText("outside") }
        val link = File(prepared.parentFile, "outside-link").toPath()
        // Windows without symlink privilege still exercises the directory-boundary assertions above.
        runCatching { Files.createSymbolicLink(link, outside.toPath()) }
        fixture.cache.release()
        assertEquals("outside", keep.readText())
    }

    private fun withFixture(maxBytes: Long = 1024, block: (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("jgenesis-cache").toFile()
        try { block(Fixture(directory, maxBytes)) } finally { directory.deleteRecursively() }
    }

    private class Fixture(val directory: File, maxBytes: Long) {
        val cacheDirectory = File(directory, "cache").apply { mkdirs() }
        val sourceDirectory = File(directory, "sources").apply { mkdirs() }
        val opens = HashMap<String, Int>()
        val cache = JGenesisContentCache(cacheDirectory, maxBytes)
        val source = object : JGenesisContentSource {
            override fun displayName(path: String): String = File(path).name
            override fun open(path: String): InputStream {
                opens[path] = (opens[path] ?: 0) + 1
                return File(path).inputStream()
            }
            override fun resolveReference(path: String, reference: String): String? =
                File(File(path).parentFile, reference).takeIf { it.isFile }?.path
        }
        fun sourceFile(name: String, contents: String): File = File(sourceDirectory, name).apply { writeText(contents) }
    }
}
