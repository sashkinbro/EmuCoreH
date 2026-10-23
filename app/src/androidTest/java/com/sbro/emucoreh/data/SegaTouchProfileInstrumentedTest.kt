package com.sbro.emucoreh.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucoreh.core.EmulationPlatform
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SegaTouchProfileInstrumentedTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "qa-sega-touch-${UUID.randomUUID()}-"
    private val preferenceNames = mutableSetOf<String>()
    private val isolated = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            preferenceNames += prefix + name
            return base.getSharedPreferences(prefix + name, mode)
        }
    }
    @After fun cleanIsolatedPreferences() {
        preferenceNames.forEach { base.deleteSharedPreferences(it) }
    }

    @Test fun platformLayoutsStayIndependentAndBackUpCustomButtons() {
        val store = SegaTouchLayoutPreferences(isolated)
        val md = EmulationPlatform.GENESIS
        val gg = EmulationPlatform.GAME_GEAR
        val defaultGg = store.load(gg)
        val layout = store.load(md).layout.copy(actionOffset = 12f to -18f, stickScale = 120)
        val custom = CustomTouchControlLibrary(controls = listOf(
            CustomTouchControl(id = "md-extra", actionId = "square", label = "A", positionX = 0.6f)))
        store.save(md, layout, custom)
        assertEquals(defaultGg, store.load(gg))
        val saved = store.load(md)
        val backup = store.exportJson()
        store.reset(md)
        assertNotEquals(saved, store.load(md))
        store.restoreJson(backup)
        assertEquals(saved, store.load(md))
        assertEquals(defaultGg, store.load(gg))
        // Old backups carry no touch-layout key and must preserve the installed profile.
        store.restoreJson(null)
        assertEquals(saved, store.load(md))
    }

    @Test fun editorSavesNotifyTheRunningPlatformObserver() = runBlocking {
        val store = SegaTouchLayoutPreferences(isolated)
        val platform = EmulationPlatform.SEGA_CD
        val expected = store.load(platform).layout.copy(centerOffset = 8f to -10f)
        val observed = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { store.observe(platform).first { it.layout.centerOffset == expected.centerOffset } }
        }
        store.save(platform, expected, CustomTouchControlLibrary.Empty)
        assertEquals(expected, observed.await().layout)
        assertEquals(SegaTouchLayouts.defaultProfile(EmulationPlatform.MASTER_SYSTEM),
            store.load(EmulationPlatform.MASTER_SYSTEM).layout)
    }
    @Test fun resetAllSegaSettingsClearsOnlyItsOwnNamespaces() {
        val platform = EmulationPlatform.GENESIS
        val store = SegaTouchLayoutPreferences(isolated)
        val default = store.load(platform)
        store.save(platform, default.layout.copy(centerOffset = 17f to -22f),
            CustomTouchControlLibrary(controls = listOf(CustomTouchControl(id = "extra", actionId = "a"))))
        com.sbro.emucoreh.core.SegaCoreOptions.set(isolated, "jgenesis_region", "japan", platform)
        com.sbro.emucoreh.core.SegaControlProfiles.save(isolated, platform, mapOf(0 to mapOf("cross" to 30)))
        val legacy = isolated.getSharedPreferences("legacy-layout-proof", Context.MODE_PRIVATE)
        legacy.edit().putString("layout", "unchanged").commit()
        com.sbro.emucoreh.core.SegaSettingsBackup.resetAll(isolated)
        assertEquals(default, store.load(platform))
        assertTrue(com.sbro.emucoreh.core.SegaCoreOptions.persistedEntries(isolated, platform).isEmpty())
        assertTrue(com.sbro.emucoreh.core.SegaControlProfiles.load(isolated, platform).isEmpty())
        assertEquals("unchanged", legacy.getString("layout", null))
    }

}
