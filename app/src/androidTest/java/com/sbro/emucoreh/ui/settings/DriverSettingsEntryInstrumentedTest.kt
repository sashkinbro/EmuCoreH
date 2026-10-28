package com.sbro.emucoreh.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucoreh.R
import com.sbro.emucoreh.core.GpuDriverCompatibility
import com.sbro.emucoreh.ui.theme.EmuCoreHTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriverSettingsEntryInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun graphicsSettingsOpenDriverCatalog() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(GpuDriverCompatibility.supportsCustomDrivers(context))
        var opened = false
        compose.setContent {
            EmuCoreHTheme {
                SettingsScreen(initialTab = "graphics", onOpenGpuDriverManager = { opened = true })
            }
        }
        val label = context.getString(R.string.settings_gpu_driver_manager_title)
        compose.waitUntil(15_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        val entry = compose.onNodeWithText(label)
        entry.performScrollTo().assertIsDisplayed()
        if (InstrumentationRegistry.getArguments().getString("captureDriverEntry") == "true") {
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "driver-settings-entry-qa.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
        entry.performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test fun model3AndSg1000ShowOnlyTheirSupportedGraphicsSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(GpuDriverCompatibility.supportsCustomDrivers(context))
        compose.setContent {
            EmuCoreHTheme { SettingsScreen(initialTab = "graphics", onOpenGpuDriverManager = {}) }
        }
        val model3 = context.getString(R.string.home_platform_model_3)
        val sg1000 = context.getString(R.string.home_platform_sg_1000)
        val renderer = context.getString(R.string.settings_renderer)
        val driver = context.getString(R.string.settings_gpu_driver_manager_title)
        compose.waitUntil(15_000) { compose.onAllNodesWithText(model3).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(model3).performScrollTo().performClick()
        compose.onNodeWithText(renderer).assertExists()
        compose.onNodeWithText(context.getString(R.string.settings_renderer_opengl)).assertExists()
        compose.onNodeWithText(context.getString(R.string.settings_renderer_vulkan)).assertExists()
        compose.onNodeWithText(driver).assertExists()
        compose.onNodeWithText(context.getString(R.string.model3_native_video)).assertDoesNotExist()
        compose.onNodeWithText(sg1000).performScrollTo().performClick()
        compose.onNodeWithText(renderer).assertExists()
        compose.onNodeWithText(driver).assertDoesNotExist()
        assertTrue("No empty SG-1000 video section", compose.onAllNodesWithText(sg1000).fetchSemanticsNodes().size == 1)
    }
}
