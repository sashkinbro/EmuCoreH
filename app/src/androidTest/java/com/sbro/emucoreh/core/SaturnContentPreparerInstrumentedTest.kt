package com.sbro.emucoreh.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SaturnContentPreparerInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rawIsoAndBinImagesReceiveBeetleCompatibleCueSheets() {
        val root = File(context.cacheDir, "saturn-content-qa-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val iso = File(root, "disc.iso").apply { writeBytes(ByteArray(2048 * 40)) }
            val isoCue = File(SaturnContentPreparer.prepare(context, iso.path))
            assertTrue(isoCue.name.endsWith(".cue"))
            val isoText = isoCue.readText()
            assertTrue("Cue: $isoText", isoText.contains("FILE \"${iso.canonicalPath.replace('\\', '/')}\" BINARY"))
            assertTrue(isoText.contains("TRACK 01 MODE1/2048"))

            val bin = File(root, "disc.bin").apply {
                val raw = ByteArray(2352 * 40)
                raw[0] = 0
                for (index in 1..10) raw[index] = 0xFF.toByte()
                raw[11] = 0
                writeBytes(raw)
            }
            val binCue = File(SaturnContentPreparer.prepare(context, bin.path))
            assertTrue(binCue.readText().contains("TRACK 01 MODE1/2352"))
        } finally {
            SaturnContentPreparer.releasePreparedContent(context)
            root.deleteRecursively()
        }
    }
}
