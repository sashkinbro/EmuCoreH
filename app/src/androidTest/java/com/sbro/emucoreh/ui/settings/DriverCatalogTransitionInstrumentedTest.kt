package com.sbro.emucoreh.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.sbro.emucoreh.R
import com.sbro.emucoreh.navigation.appScreenEnterTransition
import com.sbro.emucoreh.navigation.appScreenExitTransition
import com.sbro.emucoreh.navigation.appScreenPopEnterTransition
import com.sbro.emucoreh.navigation.appScreenPopExitTransition
import com.sbro.emucoreh.ui.theme.EmuCoreHTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriverCatalogTransitionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun backButtonKeepsCatalogVisibleDuringExitTransition() {
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
                        Box(Modifier.fillMaxSize().testTag("settings")) { Text("Settings") }
                    }
                    composable("drivers") {
                        GpuDriverScreen(onBackClick = { nav.popBackStack() })
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { nav.navigate("drivers") }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("gpu_driver_catalog_list").assertExists()
        val back = ApplicationProvider.getApplicationContext<Context>().getString(R.string.back)
        compose.onNodeWithContentDescription(back).performClick()
        compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithTag("gpu_driver_catalog_list").assertExists()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("gpu_driver_catalog_list").assertDoesNotExist()
        compose.onNodeWithTag("settings").assertExists()
    }
}
