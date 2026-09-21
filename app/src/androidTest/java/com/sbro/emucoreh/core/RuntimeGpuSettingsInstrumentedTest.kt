// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeGpuSettingsInstrumentedTest {
    @Test
    fun concurrentPrecisionAndNativeRequestsCannotPublishAnIncompatiblePair() {
        assertFalse(CoreRuntime.hasSession())
        val previous = CoreRuntime.settings.toMap()
        val previousRenderer = previous["EmuCoreH:Renderer"]
            ?: previous["EmuCoreH/GS:Renderer"]
            ?: RendererDefaults.defaultForHardware().toString()
        val callers = Executors.newFixedThreadPool(2)
        try {
            CoreRuntime.settings.clear()
            assertTrue(CoreRuntime.updateSetting("EmuCoreH", "Renderer", RendererDefaults.VULKAN.toString()))
            repeat(32) {
                assertTrue(CoreRuntime.updateSetting("EmuCoreH/GPU", "HardwareExecution", "false"))
                assertTrue(CoreRuntime.updateSetting("EmuCoreH/GPU", "PGXP", "false"))
                val ready = CountDownLatch(2)
                val release = CountDownLatch(1)
                val results = listOf("PGXP", "HardwareExecution").map { key ->
                    callers.submit(Callable {
                        ready.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        CoreRuntime.updateSetting("EmuCoreH/GPU", key, "true")
                    })
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                release.countDown()
                assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
                assertFalse(CoreRuntime.settings["EmuCoreH/GPU:PGXP"] == "true" &&
                    CoreRuntime.settings["EmuCoreH/GPU:HardwareExecution"] == "true")
            }
        } finally {
            callers.shutdown()
            assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS))
            CoreRuntime.updateSetting("EmuCoreH/GPU", "HardwareExecution", "false")
            CoreRuntime.updateSetting("EmuCoreH", "Renderer", previousRenderer)
            CoreRuntime.settings.clear()
            CoreRuntime.settings.putAll(previous)
        }
    }

    @Test
    fun nativeExecutionRejectsIncompatiblePendingSettingsWithoutChangingThem() {
        assertFalse(CoreRuntime.hasSession())
        val previous = CoreRuntime.settings.toMap()
        val previousRenderer = previous["EmuCoreH:Renderer"]
            ?: previous["EmuCoreH/GS:Renderer"]
            ?: RendererDefaults.defaultForHardware().toString()
        try {
            CoreRuntime.settings.clear()
            assertTrue(CoreRuntime.updateSetting("EmuCoreH", "Renderer", RendererDefaults.VULKAN.toString()))
            // Absent PGXP still means the existing true default, not permission
            // to silently downgrade geometry to make native execution fit.
            assertFalse(CoreRuntime.updateSetting("EmuCoreH/GPU", "HardwareExecution", "true"))
            assertFalse(CoreRuntime.settings.containsKey("EmuCoreH/GPU:HardwareExecution"))
            assertFalse(CoreRuntime.settings.containsKey("EmuCoreH/GPU:PGXP"))
            assertTrue(CoreRuntime.updateSetting("EmuCoreH/GPU", "PGXP", "false"))
            assertTrue(CoreRuntime.updateSetting("EmuCoreH", "Renderer", RendererDefaults.SOFTWARE.toString()))
            assertFalse(CoreRuntime.updateSetting("EmuCoreH/GPU", "HardwareExecution", "true"))
            assertTrue(CoreRuntime.updateSetting("EmuCoreH", "Renderer", RendererDefaults.OPENGL.toString()))
            assertTrue(CoreRuntime.updateSetting("EmuCoreH/GPU", "HardwareExecution", "true"))
            val accepted = CoreRuntime.settings.toMap()
            for (invalid in listOf("", "invalid", "1", "True", " true ")) {
                assertFalse(CoreRuntime.updateSetting("EmuCoreH/GPU", "HardwareExecution", invalid))
            }
            assertFalse(CoreRuntime.updateSetting("EmuCoreH/GPU", "PGXP", "true"))
            assertFalse(CoreRuntime.updateSetting("EmuCoreH", "Renderer", RendererDefaults.SOFTWARE.toString()))
            assertEquals(accepted, CoreRuntime.settings.toMap())
            assertTrue(CoreRuntime.updateSetting("EmuCoreH/GPU", "HardwareExecution", "false"))
            assertTrue(CoreRuntime.updateSetting("EmuCoreH/GPU", "PGXP", "true"))
            assertTrue(CoreRuntime.updateSetting("EmuCoreH", "Renderer", RendererDefaults.SOFTWARE.toString()))
        } finally {
            CoreRuntime.updateSetting("EmuCoreH/GPU", "HardwareExecution", "false")
            CoreRuntime.updateSetting("EmuCoreH", "Renderer", previousRenderer)
            CoreRuntime.settings.clear()
            CoreRuntime.settings.putAll(previous)
        }
    }

    @Test
    fun rejectedGeometrySettingPreservesTheLastAcceptedValue() {
        // Only this fresh instrumentation process's in-memory runtime settings;
        // no BIOS, emulator startup, DataStore or user preferences are touched.
        assertFalse(CoreRuntime.isRunning())
        val key = "EmuCoreH/GPU:PGXP"
        val previous = CoreRuntime.settings[key]
        try {
            for (accepted in listOf("false", "true")) {
                assertTrue(CoreRuntime.updateSetting("EmuCoreH/GPU", "PGXP", accepted))
                for (invalid in listOf("", "invalid", "1", "False", " true ")) {
                    assertFalse(CoreRuntime.updateSetting("EmuCoreH/GPU", "PGXP", invalid))
                    assertEquals("invalid '$invalid' must not replace '$accepted'",
                        accepted, CoreRuntime.settings[key])
                }
            }
        } finally {
            if (previous == null) CoreRuntime.settings.remove(key)
            else CoreRuntime.settings[key] = previous
        }
    }
}
