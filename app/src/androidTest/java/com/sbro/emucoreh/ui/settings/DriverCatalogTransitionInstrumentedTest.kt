package com.sbro.emucoreh.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import android.graphics.Bitmap
import com.sbro.emucoreh.R
import com.sbro.emucoreh.navigation.appScreenEnterTransition
import com.sbro.emucoreh.navigation.appScreenExitTransition
import com.sbro.emucoreh.navigation.appScreenPopEnterTransition
import com.sbro.emucoreh.navigation.appScreenPopExitTransition
import com.sbro.emucoreh.navigation.gpuDriverPopExitTransition
import com.sbro.emucoreh.ui.theme.EmuCoreHTheme
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DriverCatalogTransitionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun backButtonKeepsCatalogVisibleDuringExitTransition() {
        fun capture(name: String): Bitmap {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            if (InstrumentationRegistry.getArguments().getString("captureDriverTransition") == "true") {
                val context = ApplicationProvider.getApplicationContext<Context>()
                File(context.getExternalFilesDir(null), "driver-transition-$name.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            return bitmap
        }
        lateinit var nav: NavHostController
        compose.setContent {
            EmuCoreHTheme {
                nav = rememberNavController()
                NavHost(
                    navController = nav,
                    startDestination = "settings",
                    enterTransition = { appScreenEnterTransition() },
                    exitTransition = { appScreenExitTransition() },
                    popEnterTransition = { appScreenPopEnterTransition() },
                    popExitTransition = { appScreenPopExitTransition() }
                ) {
                    composable("settings") {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("settings")) { Text("Settings") }
                    }
                    composable("drivers", popExitTransition = { gpuDriverPopExitTransition() }) {
                        GpuDriverScreen(onBackClick = { nav.popBackStack() })
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { nav.navigate("drivers") }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("gpu_driver_catalog_list").assertExists()
        val before = capture("before")
        val back = ApplicationProvider.getApplicationContext<Context>().getString(R.string.back)
        compose.onNodeWithContentDescription(back).performClick()
        compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithTag("gpu_driver_catalog_list").assertExists()
        if (InstrumentationRegistry.getArguments().getString("captureDriverTransition") == "true") capture("middle").recycle()
        compose.mainClock.advanceTimeBy(32)
        if (InstrumentationRegistry.getArguments().getString("captureDriverTransition") == "true") capture("80").recycle()
        compose.mainClock.advanceTimeBy(32)
        if (InstrumentationRegistry.getArguments().getString("captureDriverTransition") == "true") capture("112").recycle()
        compose.mainClock.advanceTimeBy(32)
        val midway = capture("144")
        compose.onNodeWithTag("gpu_driver_catalog_list").assertExists()
        var changed = 0
        var samples = 0
        for (y in 0 until before.height step 40) {
            for (x in 0 until before.width step 40) {
                samples++
                if (before.getPixel(x, y) != midway.getPixel(x, y)) changed++
            }
        }
        assertTrue("Exit transition must visibly change the rendered screen", changed > samples / 5)
        before.recycle()
        midway.recycle()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("gpu_driver_catalog_list").assertDoesNotExist()
        compose.onNodeWithTag("settings").assertExists()
        if (InstrumentationRegistry.getArguments().getString("captureDriverTransition") == "true") capture("after").recycle()
    }
}
