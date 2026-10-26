package com.sbro.emucoreh.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState

/** One provider validation per startup request, independent of navigation's ordinary redraws. */
@Composable
internal fun rememberStartupDestination(
    environment: Any,
    launchIntentVersion: Int,
    load: suspend () -> StartupDestination
): State<StartupDestination?> {
    val currentLoad = rememberUpdatedState(load)
    return produceState(initialValue = null, key1 = environment, key2 = launchIntentVersion) {
        value = currentLoad.value()
    }
}
