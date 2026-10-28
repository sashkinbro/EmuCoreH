package com.sbro.emucoreh.core

import com.sbro.emucoreh.R
import org.junit.Assert.*
import org.junit.Test

class SegaCoreOptionsHelpTest {
    @Test fun everyExposedOptionHasAnExplanationRatherThanItsLabel() {
        for (platform in EmulationPlatform.entries.filter { it.isSega }) {
            for (option in SegaCoreOptions.options(platform)) {
                assertNotEquals("${platform.name}: ${option.key}", option.labelRes, option.descriptionRes)
                assertNotEquals("${platform.name}: ${option.key} needs more than a restart notice", R.string.sega_restart_required, option.descriptionRes)
            }
        }
    }
}
