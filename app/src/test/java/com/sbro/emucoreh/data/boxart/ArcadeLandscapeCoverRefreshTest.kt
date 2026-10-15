package com.sbro.emucoreh.data.boxart

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ArcadeLandscapeCoverRefreshTest {
    private val base = "https://raw.githubusercontent.com/sashkinbro/EmuCoreH-Covers/main"

    private fun withDirectory(test: (File) -> Unit) {
        val root = Files.createTempDirectory("arcade-landscape-refresh").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }

    @Test fun `replaced artwork is removed by source URL even for serial and title aliases`() = withDirectory { root ->
        val stems = listOf("anpanman2", "bingogal", "brickppl", "derbyo2k", "derbyoc",
            "derbyoc2", "derbyocw", "dragntr", "dragntr2", "dragntr3", "drbyocwa",
            "galilfac", "huhimage", "mj1", "monomedal", "oinori", "shors2k1",
            "shors2k2", "shorse", "shorsep", "shorsepr", "hopper")
        stems.forEachIndexed { index, stem ->
            val key = "SERIAL${index}_title_alias"
            File(root, "$key.url").writeText("$base/arcade/$stem.jpg")
            File(root, "$key.dark.url").writeText("$base/arcade/3d/$stem.webp")
            File(root, "$key.light.url").writeText("$base/arcade/3d/light/$stem.webp")
            listOf("online.jpg", "online.png", "dark.online.webp", "light.online.webp",
                "jpg", "jpeg", "webp", "dark.webp", "light.webp", "_light.webp").forEach { suffix ->
                File(root, if (suffix.startsWith('_')) "$key$suffix" else "$key.$suffix").writeText("old portrait")
            }
            File(root, "$key.png").writeText("legacy disc")
            File(root, "$key.disc.png").writeText("disc")
        }

        assertTrue(ArcadeLandscapeCoverRefresh.refresh(root))

        stems.indices.forEach { index ->
            val key = "SERIAL${index}_title_alias"
            assertEquals(listOf("$key.disc.png", "$key.png"),
                root.listFiles()!!.filter { it.name.startsWith("$key.") || it.name.startsWith("${key}_") }
                    .map { it.name }.sorted())
        }
    }

    @Test fun `correct games other sources and files outside managed cache are preserved`() = withDirectory { root ->
        val cache = File(root, "cache").apply { mkdir() }
        val urls = listOf(
            "$base/arcade/clubkcyc.jpg", "$base/arcade/inidv3cy.jpg",
            "$base/arcade/ikaruga.jpg", "$base/covers/anpanman2.jpg",
            "https://raw.githubusercontent.com/other/EmuCoreH-Covers/main/arcade/anpanman2.jpg",
            "$base/arcade/anpanman20.jpg", "$base/arcade/3d/other/anpanman2.webp")
        urls.forEachIndexed { index, url ->
            File(cache, "game$index.url").writeText(url)
            File(cache, "game$index.online.jpg").writeText("correct")
        }
        val custom = File(root, "filesDir").apply { mkdir() }
        File(custom, "custom.url").writeText("$base/arcade/anpanman2.jpg")
        val userArt = File(custom, "custom.online.jpg").apply { writeText("user selected") }
        File(cache, "no_provenance.online.jpg").writeText("unknown")

        ArcadeLandscapeCoverRefresh.refresh(cache)

        urls.indices.forEach { index ->
            assertEquals("correct", File(cache, "game$index.online.jpg").readText())
            assertEquals(urls[index], File(cache, "game$index.url").readText())
        }
        assertEquals("unknown", File(cache, "no_provenance.online.jpg").readText())
        assertEquals("user selected", userArt.readText())
    }

    @Test fun `completed refresh never removes the newly downloaded landscape at the same URL`() = withDirectory { root ->
        val image = File(root, "alias.online.jpg").apply { writeText("old") }
        val source = File(root, "alias.url").apply { writeText("$base/arcade/mj1.jpg") }
        assertTrue(ArcadeLandscapeCoverRefresh.refresh(root))
        assertFalse(image.exists())
        image.writeText("new landscape")
        source.writeText("$base/arcade/mj1.jpg")

        assertFalse(ArcadeLandscapeCoverRefresh.refresh(root))
        assertEquals("new landscape", image.readText())
        assertTrue(source.isFile)
    }

    @Test fun `failed removal preserves provenance and retries before marking complete`() = withDirectory { root ->
        val image = File(root, "alias.online.jpg").apply { mkdir() }
        File(image, "block-delete").writeText("keep")
        val source = File(root, "alias.url").apply { writeText("$base/arcade/mj1.jpg") }

        assertFalse(ArcadeLandscapeCoverRefresh.refresh(root))
        assertTrue(source.isFile)
        File(image, "block-delete").delete()
        image.delete()
        image.writeText("old portrait")
        assertTrue(ArcadeLandscapeCoverRefresh.refresh(root))
        assertFalse(image.exists())
        assertFalse(source.exists())
    }
}
