package com.sbro.emucoreh.ui.formats

import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucoreh.R
import com.sbro.emucoreh.ui.theme.EmuCoreHTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test

class SupportedFormatsLocalizationInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun segaInstructionsAndFormatChipsRemainReachableInAllEighteenLanguages() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val locale = mutableStateOf(Locale.ENGLISH)
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                setLocale(locale.value)
                fontScale = 1.6f
            }
            val activity = LocalContext.current
            val localized = activity.createConfigurationContext(config)
            val context = object : ContextWrapper(activity) {
                override fun getResources(): Resources = localized.resources
                override fun getAssets(): AssetManager = localized.assets
            }
            CompositionLocalProvider(LocalConfiguration provides config, LocalContext provides context,
                LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                EmuCoreHTheme { SupportedFormatsScreen(onBackClick = {}) }
            }
        }
        for (language in listOf("en", "uk", "ru", "ar", "cs", "de", "es", "fa", "fr", "hi", "id", "it", "ja", "ko", "pl", "pt", "tr", "zh")) {
            val selected = Locale.forLanguageTag(language)
            compose.runOnIdle { locale.value = selected }
            val resources = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                setLocale(selected)
            }).resources
            compose.onNodeWithText(resources.getString(R.string.formats_sega_body))
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(resources.getString(R.string.formats_model3_body))
                .performScrollTo().assertIsDisplayed()
            for (format in listOf("MD", "GEN", "SMD", "32X", "SMS", "GG", "SG", "ZIP", "CUE/BIN", "CHD", "M3U")) {
                compose.onAllNodesWithText(format).onLast().performScrollTo().assertIsDisplayed()
            }
        }
    }
}
