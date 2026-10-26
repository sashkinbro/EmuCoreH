package com.sbro.emucoreh.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucoreh.data.igdb.IgdbCatalogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class SaturnCatalogIdentityInstrumentedTest {
    @Test fun canonicalSaturnTitlesWinOverOtherGamesAliasesAndAmbiguousAliasesDoNotMatch() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "saturn-catalog-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = root
        }
        val repository = IgdbCatalogRepository(context)
        try {
            assertTrue(repository.ensureDatabaseReady())
            // Both names are also aliases of a different Saturn catalogue entry.
            assertEquals(39300L, repository.findBestMatchId(null, "Strikers 1945", "Saturn"))
            assertEquals(19734L, repository.findBestMatchId(null, "Virtua Fighter", "Saturn"))
            // An upstream executable alias belongs to four unrelated games.
            assertNull(repository.findBestMatchId(null, "dosbox.exe", "Saturn"))
            // The index records these two reviewed Saturn-only ports; retain
            // all catalog entities without choosing unrelated duplicate titles.
            assertEquals(139169L, repository.findBestMatchId(null, "Double Switch", "Saturn"))
            assertEquals(259943L, repository.findBestMatchId(null, "Doom", "Saturn"))
            assertNull(repository.findBestMatchId(null, "Dragon's Heaven", "Saturn"))
            assertNull(repository.findBestMatchId(null, "Yuukyuu Gensoukyoku 2nd Album", "Saturn"))
            assertEquals(1667L, repository.findBestMatchId(" mk\u00a081009 ", "Unrecognized filename", "Saturn"))
            assertEquals(1667L, repository.findBestMatchId(null, "Panzer Dragoon (USA) (5S).chd", "Saturn"))
            for (unknown in listOf("Daytona USA 2", "Panzer Dragoon 999", "Virtua Fighter 999",
                "Sonic R (Demo)", "Sonic R (Special Edition)")) {
                assertNull("Never guess a sequel or edition: $unknown", repository.findBestMatchId(null, unknown, "Saturn"))
            }
        } finally {
            repository.close()
            root.deleteRecursively()
        }
    }
}
