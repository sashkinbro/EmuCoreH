package com.sbro.emucoreh.ui.onboarding

import android.app.Application
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucoreh.ui.theme.EmuCoreHTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Exercises actual onboarding pages; never completes setup or changes user preferences. */
class OnboardingLocalizationInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    @After fun releaseViewModel() { InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() } }

    @Test fun allEighteenLanguagesKeepNavigationReachableWithLargeText() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        lateinit var model: OnboardingViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model = OnboardingViewModel(app)
            store.put("qa-onboarding", model)
        }
        val locale = mutableStateOf(Locale.ENGLISH)
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                setLocale(locale.value)
                fontScale = 1.6f
            }
            val context = app.createConfigurationContext(config)
            CompositionLocalProvider(LocalConfiguration provides config, LocalContext provides context,
                LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                EmuCoreHTheme { OnboardingScreen(onComplete = {}, viewModel = model) }
            }
        }
        for (language in listOf("en", "uk", "ru", "ar", "cs", "de", "es", "fa", "fr", "hi", "id", "it", "ja", "ko", "pl", "pt", "tr", "zh")) {
            compose.runOnIdle { locale.value = Locale.forLanguageTag(language) }
            for (page in 0 until 5) {
                compose.runOnIdle { model.setCurrentPage(page) }
                compose.waitForIdle()
                val navigation = compose.onNodeWithTag("onboarding_navigation").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
                assertTrue("$language page$page navigation left", navigation.left >= root.left - 1f)
                assertTrue("$language page$page navigation right", navigation.right <= root.right + 1f)
                assertTrue("$language page$page navigation top", navigation.top >= root.top - 1f)
                assertTrue("$language page$page navigation bottom", navigation.bottom <= root.bottom + 1f)
                assertTrue("$language page$page content must scroll", compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty())
            }
        }
    }
}
