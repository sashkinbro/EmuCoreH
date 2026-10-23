// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class Model3ContentCacheTest {
    // Storage/lifecycle tests use authored bytes; identity matching has separate real ZIP tests.
    private fun cache(directory: File, maxZipBytes: Long = 256L * 1024 * 1024,
        maxTotalBytes: Long = 2L * 1024 * 1024 * 1024) =
        Model3ContentCache(directory, maxZipBytes, maxTotalBytes, identify = { _, named, _ -> named })

    @Test fun directFilesStageAliasesCaseAndParentAliasesWithoutRenamingOriginals() {
        val dir = Files.createTempDirectory("model3-cache").toFile()
        try {
            val originals = File(dir, "originals").apply { mkdir() }
            val alias = File(originals, "SCUDA.ZIP").apply { writeBytes(byteArrayOf(4)) }
            val clone = File(originals, "SCUDJ.ZIP").apply { writeBytes(byteArrayOf(5)) }
            val uppercase = File(originals, "SRALLY2.ZIP").apply { writeBytes(byteArrayOf(6)) }
            val cache = cache(File(dir, "cache").apply { mkdir() })
            val stagedAlias = cache.prepareFile(alias)
            val stagedClone = cache.prepareFile(clone)
            val stagedUppercase = cache.prepareFile(uppercase)
            assertEquals("scud.zip", stagedAlias.name)
            assertEquals("scuddx.zip", stagedClone.name)
            assertEquals(setOf("scuddx.zip", "scud.zip"), stagedClone.parentFile!!.list()!!.toSet())
            assertEquals("srally2.zip", stagedUppercase.name)
            assertArrayEquals(byteArrayOf(4), File(stagedClone.parentFile, "scud.zip").readBytes())
            assertTrue(cache.release())
            assertTrue(alias.isFile && clone.isFile && uppercase.isFile)
            assertArrayEquals(byteArrayOf(4), alias.readBytes())
            assertFalse(stagedAlias.exists() || stagedClone.exists() || stagedUppercase.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun canonicalDirectFamilyDoesNotCopyButAliasParentRequiresStaging() {
        val dir = Files.createTempDirectory("model3-cache").toFile()
        try {
            val cache = cache(File(dir, "cache").apply { mkdir() })
            val canonical = File(dir, "srally2.zip").apply { writeBytes(byteArrayOf(1)) }
            assertEquals(canonical.canonicalFile, cache.prepareFile(canonical))
            val clone = File(dir, "scuddx.zip").apply { writeBytes(byteArrayOf(2)) }
            File(dir, "scuda.zip").writeBytes(byteArrayOf(3))
            assertNotEquals(clone.canonicalFile, cache.prepareFile(clone))
            File(dir, "scud.zip").writeBytes(byteArrayOf(4))
            assertEquals(clone.canonicalFile, cache.prepareFile(clone))
            assertTrue(cache.release())
            assertTrue(canonical.isFile && clone.isFile)
        } finally { dir.deleteRecursively() }
    }

    @Test fun parentSourceNamesAreCanonicalFirstAndEveryAliasResolvesToSameSet() {
        val scud = Model3Romsets.find("scud.zip")!!
        assertEquals(listOf("scud.zip", "scuda.zip"), Model3Romsets.sourceNames(scud))
        Model3Romsets.games.forEach { game ->
            val names = Model3Romsets.sourceNames(game)
            assertEquals("${game.name}.zip", names.first())
            names.forEach { assertEquals(game, Model3Romsets.find(it)) }
            assertEquals(names.size, names.distinct().size)
        }
    }

    @Test fun directStagingRejectsOversizeAndMissingParentsWithoutChangingOriginals() {
        val dir = Files.createTempDirectory("model3-cache").toFile()
        try {
            val cache = cache(File(dir, "cache").apply { mkdir() }, maxZipBytes = 2)
            val oversize = File(dir, "SCUDA.ZIP").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val clone = File(dir, "SCUDJ.ZIP").apply { writeBytes(byteArrayOf(4)) }
            assertTrue(runCatching { cache.prepareFile(oversize) }.isFailure)
            oversize.delete()
            assertTrue(runCatching { cache.prepareFile(clone) }.isFailure)
            assertArrayEquals(byteArrayOf(4), clone.readBytes())
            assertTrue(cache.release())
            assertTrue(File(dir, "cache/model3-content").listFiles().orEmpty().isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun canonicalizesSelectedAliasesAndCaseWithoutChangingSourceIdentity() {
        val dir = Files.createTempDirectory("model3-cache").toFile()
        try {
            val cache = cache(dir)
            for ((sourceName, expected) in listOf("SCUDA.ZIP" to "scud.zip", "SRALLY2.ZIP" to "srally2.zip")) {
                val source = Source(mapOf("original" to sourceName), mapOf("original" to byteArrayOf(7)))
                val prepared = cache.prepare("original", source)
                assertEquals(expected, prepared.name)
                assertEquals(sourceName, source.displayName("original"))
                assertArrayEquals(byteArrayOf(7), prepared.readBytes())
            }
            assertTrue(cache.release())
        } finally { dir.deleteRecursively() }
    }

    @Test fun resolvesKnownParentAliasButStagesCanonicalParentName() {
        val dir = Files.createTempDirectory("model3-cache").toFile()
        try {
            val source = Source(mapOf("clone" to "SCUDJ.ZIP", "parent" to "SCUDA.ZIP", "other" to "srally2.zip"),
                mapOf("clone" to byteArrayOf(1), "parent" to byteArrayOf(2), "other" to byteArrayOf(3)))
            val prepared = cache(dir).prepare("clone", source)
            assertEquals("scuddx.zip", prepared.name)
            assertEquals(setOf("scuddx.zip", "scud.zip"), prepared.parentFile!!.list()!!.toSet())
            assertEquals(listOf("clone", "parent"), source.opened)
        } finally { dir.deleteRecursively() }
    }
    private class Source(val names: Map<String, String>, val data: Map<String, ByteArray>) : Model3ContentSource {
        val opened = mutableListOf<String>()
        override fun displayName(path: String) = names.getValue(path)
        override fun open(path: String): InputStream? { opened += path; return data[path]?.inputStream() }
        override fun resolveReference(path: String, reference: String) = names.entries.firstOrNull { it.value.equals(reference, true) }?.key
    }

    @Test fun copiesOnlySelectedCloneAndParentWithOriginalBasename() {
        val dir = Files.createTempDirectory("model3-cache").toFile()
        try {
            val source = Source(mapOf("clone" to "dayto2pe.zip", "parent" to "daytona2.zip", "other" to "srally2.zip"),
                mapOf("clone" to byteArrayOf(1), "parent" to byteArrayOf(2), "other" to byteArrayOf(3)))
            val cache = cache(dir)
            val selected = cache.prepare("clone", source)
            assertEquals("dayto2pe.zip", selected.name)
            assertEquals(setOf("dayto2pe.zip", "daytona2.zip"), selected.parentFile!!.list()!!.toSet())
            assertEquals(listOf("clone", "parent"), source.opened)
            assertArrayEquals(byteArrayOf(1), selected.readBytes())
            assertTrue(cache.release())
            assertFalse(selected.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun freshLaunchKeepsOldFilesUntilExplicitUnloadRelease() {
        val dir = Files.createTempDirectory("model3-cache").toFile()
        try {
            val source = Source(mapOf("set" to "srally2.zip"), mapOf("set" to byteArrayOf(9)))
            val cache = cache(dir)
            val old = cache.prepare("set", source)
            val next = cache.prepare("set", source)
            assertNotEquals(old.parentFile, next.parentFile)
            assertTrue(cache.cleanupAbandoned())
            assertTrue(old.isFile && next.isFile)
            assertTrue(cache.release())
            assertFalse(old.exists() || next.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsTraversalUnknownZipMissingParentAndLimitsWithoutDamagingActiveContent() {
        val dir = Files.createTempDirectory("model3-cache").toFile()
        try {
            val cache = cache(dir, maxZipBytes = 2, maxTotalBytes = 3)
            val good = Source(mapOf("good" to "srally2.zip"), mapOf("good" to byteArrayOf(1)))
            val active = cache.prepare("good", good)
            listOf("../srally2.zip", "C:srally2.zip", "srally2.zip/evil", "unknown.zip", "srally2.7z").forEach { name ->
                assertTrue(runCatching { cache.prepare("bad", Source(mapOf("bad" to name), mapOf("bad" to byteArrayOf(1)))) }.isFailure)
            }
            assertTrue(runCatching { cache.prepare("clone", Source(mapOf("clone" to "dayto2pe.zip"), mapOf("clone" to byteArrayOf(1)))) }.isFailure)
            assertTrue(runCatching { cache.prepare("big", Source(mapOf("big" to "srally2.zip"), mapOf("big" to byteArrayOf(1, 2, 3)))) }.isFailure)
            val family = Source(mapOf("clone" to "dayto2pe.zip", "parent" to "daytona2.zip"), mapOf("clone" to byteArrayOf(1, 2), "parent" to byteArrayOf(1, 2)))
            assertTrue(runCatching { cache.prepare("clone", family) }.isFailure)
            assertArrayEquals(byteArrayOf(1), active.readBytes())
            assertEquals(1, File(dir, "model3-content").listFiles()!!.size)
        } finally { dir.deleteRecursively() }
    }
}
