package com.sbro.emucoreh.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Compose producer/recomposition behavior without DataStore or provider/user-data changes. */
class StartupDestinationStateInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun redrawDoesNotRepeatFolderReadsButNewLaunchIntentRevalidates() {
        val environment = Any()
        val appearance = mutableIntStateOf(0)
        val intent = mutableIntStateOf(0)
        val hasFolder = mutableStateOf(true)
        val probes = AtomicInteger()
        val published = AtomicReference<StartupDestination?>()
        val secondProbeRelease = CompletableDeferred<Unit>()
        var renderedAppearance = -1
        compose.setContent {
            val currentAppearance = appearance.intValue
            val currentIntent = intent.intValue
            val currentHasFolder = hasFolder.value
            SideEffect { renderedAppearance = currentAppearance }
            val destination = rememberStartupDestination(environment, currentIntent) {
                check(currentAppearance >= 0) // capture ordinary surrounding UI state
                withContext(Dispatchers.IO) {
                    probes.incrementAndGet()
                    if (currentIntent > 0) secondProbeRelease.await()
                    if (currentHasFolder) StartupDestination.HOME else StartupDestination.ONBOARDING
                }
            }
            SideEffect { published.set(destination.value) }
            Text("appearance=$currentAppearance destination=${destination.value}", Modifier.testTag("startup"))
        }
        try {
            compose.waitUntil(5000) { published.get() == StartupDestination.HOME }
            compose.onNodeWithTag("startup").assertTextContains("HOME", substring = true)
            compose.runOnIdle { appearance.intValue++ }
            compose.waitForIdle()
            assertEquals(1, renderedAppearance)
            assertEquals("An unrelated redraw must not traverse game folders again", 1, probes.get())

            compose.runOnIdle { hasFolder.value = false; intent.intValue++ }
            compose.waitUntil(5000) { probes.get() == 2 }
            compose.onNodeWithTag("startup").assertTextContains("HOME", substring = true)
            compose.runOnIdle { appearance.intValue++ }
            compose.waitForIdle()
            assertEquals("Redraw cannot restart even a slow provider check", 2, probes.get())
            secondProbeRelease.complete(Unit)
            compose.waitUntil(5000) { published.get() == StartupDestination.ONBOARDING }
            compose.onNodeWithTag("startup").assertTextContains("ONBOARDING", substring = true)
        } finally { secondProbeRelease.complete(Unit) }
    }
}
