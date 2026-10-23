// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import java.nio.file.Files
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class Model3ContentIdentityTest {
    private val parent = Model3ProgramFingerprint("base", "", setOf(1, 2, 3, 4), setOf(1, 2, 3, 4))
    private val clone = Model3ProgramFingerprint("clone", "base", setOf(5, 6, 7, 8), setOf(5, 6, 7, 8))
    private val split = Model3ProgramFingerprint("split", "base", setOf(9, 10), setOf(1, 2, 9, 10))
    private val catalog = listOf(parent, clone, split)

    @Test fun resolvesOnlyCompleteUniqueAlternateAndPreservesMatchingNamedSet() {
        assertEquals("clone", Model3ContentIdentity.identify("base", clone.effective, catalog) { null })
        assertEquals("base", Model3ContentIdentity.identify("base", parent.effective + clone.effective, catalog) { null })
        assertNull(Model3ContentIdentity.identify("base", setOf(5, 6, 7), catalog) { null })
        assertNull(Model3ContentIdentity.identify("base", clone.effective + split.effective, catalog) { null })
    }

    @Test fun splitCloneNeedsItsOwnProgramFilesAndRegisteredParentCompletion() {
        assertEquals("split", Model3ContentIdentity.identify("base", split.local, catalog) { if (it == "base") parent.effective else null })
        assertNull(Model3ContentIdentity.identify("split", split.local, catalog) { null })
        assertNull(Model3ContentIdentity.identify("split", setOf(9), catalog) { parent.effective })
        assertNull(Model3ContentIdentity.identify("base", emptySet(), catalog) { parent.effective })
    }

    @Test fun zipInspectionReadsAuthoredCentralDirectoryWithoutExtractingEntries() {
        val file = Files.createTempFile("model3-crcs", ".zip").toFile()
        try {
            val bytes = byteArrayOf(7, 11, 19)
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("authored.bin")); zip.write(bytes); zip.closeEntry()
            }
            val crc = CRC32().apply { update(bytes) }.value
            assertEquals(setOf(crc), Model3ContentIdentity.readCrcs(file))
        } finally { file.delete() }
    }

    @Test fun generatedMetadataCoversEveryRegisteredGameWithFourEffectiveProgramCrcs() {
        val generated = Model3ProgramCrcs.fingerprints
        assertEquals(Model3Romsets.games.map { it.name }.toSet(), generated.map { it.name }.toSet())
        assertEquals(generated.size, generated.map { it.name }.distinct().size)
        generated.forEach { game ->
            assertEquals(4, game.effective.size)
            assertTrue(game.effective.containsAll(game.local))
            assertEquals(Model3Romsets.find("${game.name}.zip")!!.parent, game.parent)
        }
        assertEquals(2, generated.single { it.name == "ecau" }.local.size)
    }

    private fun writeZip(file: File, seed: Int): Set<Long> {
        val crcs = mutableSetOf<Long>()
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(4) { index ->
                val bytes = byteArrayOf(seed.toByte(), index.toByte(), 37)
                crcs += CRC32().apply { update(bytes) }.value
                zip.putNextEntry(ZipEntry("authored-$index.bin")); zip.write(bytes); zip.closeEntry()
            }
        }
        return crcs
    }

    @Test fun realZipStagingUsesVerifiedAlternateAndRechecksModifiedOriginalWithoutPersistentCache() {
        val dir = Files.createTempDirectory("model3-identity").toFile()
        try {
            val source = File(dir, "scud.zip")
            val base = writeZip(source, 11)
            val alternate = writeZip(File(dir, "authored.zip"), 29)
            val definitions = listOf(Model3ProgramFingerprint("scud", "", base, base),
                Model3ProgramFingerprint("scudau", "scud", alternate, alternate))
            val cache = Model3ContentCache(File(dir, "cache").apply { mkdir() }, identify = { file, named, parent ->
                Model3ContentIdentity.identify(named.name, Model3ContentIdentity.readCrcs(file), definitions) { name ->
                    parent(Model3Romsets.find("$name.zip")!!)?.let(Model3ContentIdentity::readCrcs)
                }?.let { Model3Romsets.find("$it.zip") }
            })
            assertEquals("scud", cache.resolveFile(source)!!.name)
            writeZip(source, 29)
            assertEquals("scudau", cache.resolveFile(source)!!.name)
            val staged = cache.prepareFile(source)
            assertEquals("scudau.zip", staged.name)
            assertEquals(setOf("scudau.zip", "scud.zip"), staged.parentFile!!.list()!!.toSet())
            assertEquals(alternate, Model3ContentIdentity.readCrcs(staged))
            assertEquals("scud.zip", source.name)
            assertTrue(cache.release())
            assertFalse(staged.exists())
            assertEquals(alternate, Model3ContentIdentity.readCrcs(source))
        } finally { dir.deleteRecursively() }
    }

    @Test fun explicitInspectionRemovesOnlyItsFilesAndDoesNotCopyUnneededParent() {
        val dir = Files.createTempDirectory("model3-inspection").toFile()
        try {
            val selected = File(dir, "authored.zip")
            val base = writeZip(selected, 13)
            val alternateFile = File(dir, "alternate.zip")
            val alternate = writeZip(alternateFile, 31)
            val definitions = listOf(Model3ProgramFingerprint("scud", "", base, base),
                Model3ProgramFingerprint("scudau", "scud", alternate, alternate))
            val cache = Model3ContentCache(File(dir, "cache").apply { mkdir() }, identify = { file, named, _ ->
                Model3ContentIdentity.identify(named.name, Model3ContentIdentity.readCrcs(file), definitions) { null }
                    ?.let { Model3Romsets.find("$it.zip") }
            })
            var bytes = selected.readBytes()
            var opens = 0
            val source = object : Model3ContentSource {
                override fun displayName(path: String) = "scud.zip"
                override fun open(path: String) = bytes.inputStream().also { opens++ }
                override fun resolveReference(path: String, reference: String): String? = error("Parent unnecessary for four selected program CRCs")
            }
            val active = cache.prepare("selected", source)
            bytes = alternateFile.readBytes()
            assertEquals("scudau", cache.resolveExpectedSet("selected", source))
            assertEquals(2, opens)
            assertTrue(active.isFile)
            assertEquals(1, File(dir, "cache/model3-content").listFiles()!!.size)
            assertTrue(cache.release())
        } finally { dir.deleteRecursively() }
    }
}
