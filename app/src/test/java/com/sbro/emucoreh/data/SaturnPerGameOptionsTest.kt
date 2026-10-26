package com.sbro.emucoreh.data

import com.sbro.emucoreh.core.FlycastCoreOptions
import com.sbro.emucoreh.core.SaturnCoreOptions
import org.junit.Assert.*
import org.junit.Test

class SaturnPerGameOptionsTest {
    @Test fun everySaturnOptionSurvivesProfileBackupAndLaunchFiltering() {
        val overrides = SaturnCoreOptions.options().associate { option -> option.key to option.choices.last().value }
        val profile = PerGameSettings("/saturn/game.chd", "Saturn game", coreOptions = overrides,
            providedKeys = setOf("coreOptions"))
        val restored = profile.toJson().toPerGameSettings()
        assertEquals(overrides, restored.coreOptions)
        // The launch path excludes app-managed Flycast keys, preserving independent core overrides.
        assertEquals(overrides, restored.coreOptions.filterKeys { !FlycastCoreOptions.isManagedKey(it) })
    }
}
