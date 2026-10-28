package com.sbro.emucoreh.ui.settings

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucoreh.R
import com.sbro.emucoreh.core.GpuDriverManager
import com.sbro.emucoreh.core.RendererDefaults
import com.sbro.emucoreh.ui.theme.EmuCoreHTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpuDriverScreenInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun perGameCatalogOffersSystemGlobalAndInstalledDriver() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val source = File(InstrumentationRegistry.getArguments().getString("driverQaArchive").orEmpty())
        assumeTrue(source.isFile && source.canRead())
        val archive = File(app.cacheDir, "EmuCoreH-screen-qa-${System.nanoTime()}.zip")
        source.copyTo(archive)
        val manager = GpuDriverManager(app)
        val name = manager.installFromArchive(archive)
        val viewModel = SettingsViewModel(app)
        viewModel.refreshInstalledGpuDrivers()
        var selected: String? = "initial"
        var inherited = false
        try {
            composeRule.setContent {
                EmuCoreHTheme {
                    GpuDriverScreen(onBackClick = {}, rendererOverride = RendererDefaults.VULKAN,
                        onSelectDriverForGame = { selected = it }, onUseGlobalForGame = { inherited = true },
                        viewModel = viewModel)
                }
            }
            composeRule.onNodeWithText(app.getString(R.string.settings_use_global)).performScrollTo().performClick()
            composeRule.runOnIdle { assertEquals(true, inherited) }
            composeRule.onNodeWithText(app.getString(R.string.settings_gpu_driver_system)).performScrollTo().performClick()
            composeRule.runOnIdle { assertEquals(null, selected) }
            composeRule.onNodeWithText(name).performScrollTo().performClick()
            composeRule.runOnIdle { assertEquals(name, selected) }
            composeRule.onNodeWithText(app.getString(R.string.settings_gpu_driver_catalog)).performScrollTo().assertIsDisplayed()
        } finally {
            manager.remove(name)
            archive.delete()
        }
    }
}
