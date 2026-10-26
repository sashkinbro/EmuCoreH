package com.sbro.emucoreh.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverSettingsConfigurationTest {
    @Test fun retainedCollectorRefreshesSystemThemeWithoutPreferenceWriteAndDeduplicatesUiMode() = runBlocking {
        val snapshots = MutableStateFlow("unchanged preferences")
        val uiMode = MutableStateFlow(16)
        var systemLight = true
        var reads = 0
        val observed = mutableListOf<CoverDisplaySettings>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            coverSettingsOnConfiguration(snapshots, uiMode) {
                reads++
                CoverDisplaySettings(true, 3, systemLight)
            }.collect { observed += it }
        }
        try {
            repeat(5) { yield() }
            assertEquals(listOf(true), observed.map { it.isLight })
            systemLight = false
            uiMode.value = 32
            repeat(5) { yield() }
            assertEquals("Retained preferences must be resolved again on night-mode recreation", listOf(true, false), observed.map { it.isLight })
            uiMode.value = 32
            repeat(5) { yield() }
            assertEquals("An ordinary redraw cannot reread settings", 2, reads)
            assertEquals("unchanged preferences", snapshots.value)
        } finally { job.cancel() }
    }
}
