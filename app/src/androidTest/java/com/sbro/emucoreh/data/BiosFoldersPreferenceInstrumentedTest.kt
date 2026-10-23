package com.sbro.emucoreh.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiosFoldersPreferenceInstrumentedTest {
    @Test fun biosFoldersAccumulateIndependentlyAndCanBeRemoved() = runBlocking {
        val preferences = AppPreferences(ApplicationProvider.getApplicationContext())
        val previous = preferences.biosPaths.first()
        val first = "content://bios/folder-one"
        val second = "content://bios/folder-two"
        try {
            preferences.setBiosPaths(emptyList())
            preferences.addBiosPath(first)
            preferences.addBiosPath(second)
            assertEquals(listOf(first, second), preferences.biosPaths.first())
            assertEquals(second, preferences.biosPath.first())

            preferences.removeBiosPath(first)
            assertEquals(listOf(second), preferences.biosPaths.first())

            preferences.addBiosPath(second)
            assertEquals(listOf(second), preferences.biosPaths.first())
        } finally {
            preferences.setBiosPaths(previous)
        }
    }
}
