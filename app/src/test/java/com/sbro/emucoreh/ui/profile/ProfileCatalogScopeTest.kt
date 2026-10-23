package com.sbro.emucoreh.ui.profile

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProfileCatalogScopeTest {
    private val games by lazy {
        val file = listOf(File("src/main/assets/catalog/arcade_games.json"),
            File("app/src/main/assets/catalog/arcade_games.json")).first(File::isFile)
        JSONObject(file.readText()).getJSONObject("games")
    }

    private fun bios(romset: String): String? = games.optJSONObject(romset.lowercase())
        ?.optString("bios")?.takeIf(String::isNotBlank)

    @Test fun verifiedLegacyArcadeRecordsUseTheirHardwareRatherThanDreamcast() {
        assertEquals("Sega Naomi 2", profileCatalogScope(null, true, "vf4", ::bios))
        assertEquals("Sega Naomi 2", profileCatalogScope(null, true, "vf4evoa", ::bios))
        assertEquals("Sega Naomi", profileCatalogScope(null, true, "ikaruga", ::bios))
        assertEquals("Sammy Atomiswave", profileCatalogScope(null, true, "dolphin", ::bios))
        assertNull(profileCatalogScope(null, true, "unverified_archive", ::bios))
        assertNull(profileCatalogScope(null, true, null, ::bios))
    }

    @Test fun explicitPlatformsAndLegacyDreamcastRecordsKeepTheirScope() {
        val rejectLookup: (String) -> String? = { error("Explicit and Dreamcast scopes must not inspect arcade registry") }
        assertEquals("Sega Model 3", profileCatalogScope("MODEL_3", true, "M3-SRALLY2", rejectLookup))
        assertEquals("Genesis", profileCatalogScope("GENESIS", false, "ANY", rejectLookup))
        assertEquals("Sega Naomi", profileCatalogScope("NAOMI", true, "vf4", rejectLookup))
        assertEquals("Dreamcast", profileCatalogScope(null, false, "MK-51035", rejectLookup))
    }
}
