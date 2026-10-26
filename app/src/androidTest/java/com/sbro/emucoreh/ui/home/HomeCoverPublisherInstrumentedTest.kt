package com.sbro.emucoreh.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Exercises Home's production publisher with a real Compose consumer, without app preferences. */
class HomeCoverPublisherInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cachedGamesRemainInteractiveDuringStalledCoverIoAndOldRootCannotReplaceNewRoot() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val root = mutableStateOf("old")
        val displayed = AtomicReference<List<String>>(emptyList())
        compose.setContent {
            val scope = rememberCoroutineScope()
            val publisher = remember { HomeCoverPublisher<String>(scope) }
            var games by remember { mutableStateOf(emptyList<String>()) }
            var clicks by remember { mutableIntStateOf(0) }
            val activeRoot = root.value
            SideEffect { displayed.set(games) }
            LaunchedEffect(activeRoot) {
                publisher.publish(listOf("$activeRoot cached game"), activeRoot,
                    publishCached = { games = it },
                    resolve = {
                        if (activeRoot == "old") withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                            finished.complete(Unit)
                        }
                        listOf("$activeRoot resolved game")
                    },
                    publishResolved = { games = it; true })
            }
            Column {
                games.forEach { game ->
                    Text(game, Modifier.testTag("game").clickable { clicks++ })
                }
                Text("$clicks", Modifier.testTag("clicks"))
            }
        }
        try {
            compose.waitUntil(5000) { entered.isCompleted }
            compose.onNodeWithTag("game").assertIsDisplayed().assertTextEquals("old cached game").performClick()
            compose.onNodeWithTag("clicks").assertTextEquals("1")
            compose.runOnIdle { root.value = "new" }
            compose.waitUntil(5000) { displayed.get() == listOf("new resolved game") }
            release.complete(Unit)
            compose.waitUntil(5000) { finished.isCompleted }
            compose.waitForIdle()
            compose.onNodeWithTag("game").assertTextEquals("new resolved game").performClick()
            compose.onNodeWithTag("clicks").assertTextEquals("2")
        } finally { release.complete(Unit) }
    }
}
