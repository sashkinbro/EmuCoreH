package com.sbro.emucoreh.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PpssppOptionLocalizationInstrumentedTest {
    @Test
    fun allSupportedLanguagesResolveVisibleCoreOptions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val failures = PpssppCoreOptionLocalization.coverageAll(context.assets).mapNotNull { coverage ->
            val missing = coverage.untranslatedLabels + coverage.untranslatedChoices
            if (missing.isEmpty()) null else "${coverage.language}: ${missing.distinct()}"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun visibleOptionTitlesHaveConsistentInitialCapitalization() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val failures = PpssppCoreOptionLocalization.coverageAll(assets).flatMap { coverage ->
            PpssppCoreOptions.all().mapNotNull { option ->
                val title = PpssppCoreOptionLocalization.resolveForLanguage(assets, coverage.language, option).label
                val letter = title.firstOrNull(Char::isLetter)
                if (letter?.isLowerCase() == true) "${coverage.language}: ${option.key} = $title" else null
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
