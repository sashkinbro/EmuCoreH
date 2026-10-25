package com.sbro.emucoreh.data

import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.core.GameMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryScanMetadataTest {
    @Test
    fun discLibrariesCanBeListedWithoutReadingDiscMetadata() {
        for (platform in listOf(EmulationPlatform.DREAMCAST, EmulationPlatform.SEGA_CD)) {
            val metadata = readLibraryScanMetadata(
                platform = platform,
                inferFromName = { GameMetadata("My Game", null) },
                readPlatformMetadata = { error("Bulk scan opened disc metadata for $platform") },
                learnedSerial = { "T-12345" }
            )
            assertEquals(GameMetadata("My Game", "T-12345"), metadata)
        }
    }

    @Test
    fun cartridgeHeadersAndModel3CatalogTitlesRemainAvailable() {
        for (platform in EmulationPlatform.entries.filterNot { it.supportsDisc }) {
            assertEquals(GameMetadata("Verified platform title", "verified-serial"), readLibraryScanMetadata(
                platform,
                inferFromName = { GameMetadata("File name", null) },
                readPlatformMetadata = { GameMetadata("Verified platform title", "verified-serial") },
                learnedSerial = { error("Serial already known") }
            ))
        }
    }

    @Test
    fun anExplicitSerialInTheFilenameTakesPriorityOverALearnedSerial() {
        assertEquals(GameMetadata("My Game", "T-99999"), readLibraryScanMetadata(
            EmulationPlatform.DREAMCAST,
            inferFromName = { GameMetadata("My Game", "T-99999") },
            readPlatformMetadata = { error("Disc metadata should be deferred") },
            learnedSerial = { error("Serial already known") }
        ))
    }
}
