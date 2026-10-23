package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class Model3RomsetsTest {
    @Test fun verifiedLegacyNamesResolveToCanonicalDefinitions() {
        assertEquals("getbassur", Model3Romsets.find("bass.zip")?.name)
        assertEquals("scuddx", Model3Romsets.find("SCUDJ.ZIP")?.name)
        assertEquals("dirtdvlsau", Model3Romsets.find("dirtdvlsa.zip")?.name)
        assertEquals("srally2dx", Model3Romsets.find("srally2x.zip")?.name)
        assertEquals(EmulationPlatform.MODEL_3, SegaPlatformDetector.detectName("lostwsgo.zip"))
        assertNull(Model3Romsets.find("bass.md"))
        assertNull(Model3Romsets.find("unknown.zip"))
        assertEquals(EmulationPlatform.DREAMCAST, SegaPlatformDetector.detectName("naomi.zip"))
    }
}
