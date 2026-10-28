package com.sbro.emucoreh.ui.settings

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Build
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.sbro.emucoreh.ui.theme.EmuCoreHTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriverCatalogInsetsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun catalogExtendsBehindNavigationBar() {
        val viewModel = SettingsViewModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent {
            val activity = LocalContext.current as Activity
            SideEffect {
                WindowCompat.setDecorFitsSystemWindows(activity.window, false)
                activity.window.navigationBarColor = Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    activity.window.isNavigationBarContrastEnforced = false
                }
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    .hide(WindowInsetsCompat.Type.statusBars())
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    .isAppearanceLightNavigationBars = false
            }
            EmuCoreHTheme { GpuDriverScreen(onBackClick = {}, viewModel = viewModel) }
        }
        if (InstrumentationRegistry.getArguments().getString("captureDriverInsets") == "true") {
            compose.waitUntil(30000) { viewModel.uiState.value.remoteGpuDrivers.isNotEmpty() }
        }
        compose.onNodeWithTag("gpu_driver_catalog_list").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val bottom = compose.onNodeWithTag("gpu_driver_catalog_list")
            .fetchSemanticsNode().boundsInWindow.bottom
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertTrue("Catalog must extend to the bottom of the screen: $bottom / ${screenshot.height}", bottom >= screenshot.height - 1)
        if (InstrumentationRegistry.getArguments().getString("captureDriverInsets") == "true") {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "driver-catalog-insets-qa.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        screenshot.recycle()
    }
}
