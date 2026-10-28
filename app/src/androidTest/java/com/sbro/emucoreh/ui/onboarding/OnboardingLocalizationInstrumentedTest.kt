package com.sbro.emucoreh.ui.onboarding

import android.app.Application
import android.content.res.Configuration
import android.content.ContextWrapper
import android.content.res.Resources
import android.content.res.AssetManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucoreh.ui.theme.EmuCoreHTheme
import com.sbro.emucoreh.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import java.io.File

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
            val activityContext = LocalContext.current
            val localized = activityContext.createConfigurationContext(config)
            // Keep the Activity in the wrapper chain for result/back registries.
            val context = object : ContextWrapper(activityContext) {
                override fun getResources(): Resources = localized.resources
                override fun getAssets(): AssetManager = localized.assets
            }
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
                if (page == 4) {
                    // This is the real folder setup page; catch duplicate guides and a picker
                    // pushed beyond reach by long translations or accessibility font scaling.
                    compose.onAllNodesWithTag("supported_systems_card").assertCountEquals(1)
                    val translated = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                        setLocale(Locale.forLanguageTag(language))
                    }).resources
                    compose.onNodeWithText(translated.getString(R.string.onboarding_supported_consoles))
                        .performScrollTo().assertIsDisplayed()
                    if (language in setOf("en", "uk")) captureSetup(language, "guide-top")
                    compose.onNodeWithText(translated.getString(R.string.formats_title))
                        .performScrollTo().performClick()
                    compose.onNodeWithText(".sg", substring = true)
                        .performScrollTo().assertIsDisplayed()
                    if (language in setOf("en", "uk")) captureSetup(language, "guide-formats")
                    compose.onNodeWithText(translated.getString(R.string.onboarding_games_title))
                        .performScrollTo().assertIsDisplayed()
                    compose.onNodeWithTag("onboarding_navigation").assertIsDisplayed()
                    if (language in setOf("en", "uk")) captureSetup(language, "folder-picker")
                }
            }
        }
    }

    private fun captureSetup(language: String, section: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "qa-supported-systems").apply { mkdirs() }
        File(directory, "$language-large-text-$section.png").outputStream().use { output ->
            assertTrue(compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        }
    }
}
