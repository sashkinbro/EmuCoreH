package com.sbro.emucoreh.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreMaintenanceTest {

    @Test
    fun `reset clears regenerable files and keeps user data`() {
        val root = Files.createTempDirectory("core-maintenance").toFile()
        try {
            val cache = File(root, "cache").apply { mkdirs() }
            File(cache, "baked_shaders.bin").writeText("shaders")
            File(File(cache, "achievement_images").apply { mkdirs() }, "badge.png").writeText("badge")

            val memcards = File(root, "memcards").apply { mkdirs() }
            val memoryCard = File(memcards, "card.vmu").apply { writeText("memory card") }
            val sstates = File(root, "sstates").apply { mkdirs() }
            val saveState = File(sstates, "game.rstate").apply { writeText("state") }
            val resources = File(root, "resources").apply { mkdirs() }
            val patches = File(resources, "patches.zip").apply { writeText("patches") }

            val deletedFiles = clearRegenerableCoreState(root)

            assertEquals(2, deletedFiles)
            assertTrue(cache.isDirectory)
            assertFalse(File(cache, "baked_shaders.bin").exists())
            assertFalse(File(cache, "achievement_images").exists())
            assertTrue(memoryCard.isFile)
            assertEquals("memory card", memoryCard.readText())
            assertTrue(saveState.isFile)
            assertTrue(patches.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `reset tolerates a data root without regenerable directories`() {
        val root = Files.createTempDirectory("core-maintenance-empty").toFile()
        try {
            File(root, "memcards").mkdirs()
            assertEquals(0, clearRegenerableCoreState(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
