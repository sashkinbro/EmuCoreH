// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeFailureLifecycleInstrumentedTest {
    @Test
    fun stoppedWorkerRetainsOwnershipUntilBridgeShutdown() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousSettings = CoreRuntime.settings.toMap()
        val previousRenderer = previousSettings["EmuCoreH:Renderer"]
            ?: previousSettings["EmuCoreH/GS:Renderer"]
            ?: RendererDefaults.defaultForHardware().toString()
        val rom = File.createTempFile("failure-rom-", ".bin", context.cacheDir)
        val state = File.createTempFile("failure-state-", ".rstate", context.cacheDir)
        try {
            CoreRuntime.shutdown()
            CoreRuntime.detachSurface()
            // Owned RAM-increment loop; never load private BIOS/cards/preferences.
            val bytes = ByteBuffer.allocate(512 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            listOf(0x3c08a000, 0x24090000, 0x25290001, 0xad090000.toInt(),
                0x1000fffd, 0).forEach { bytes.putInt(it) }
            rom.writeBytes(bytes.array())
            CoreRuntime.settings.clear()
            CoreRuntime.initialize(context)
            assertTrue(CoreRuntime.updateSetting("Folders", "Bios", rom.parent!!))
            assertTrue(CoreRuntime.updateSetting("Filenames", "BIOS", rom.name))
            assertTrue(CoreRuntime.updateSetting("EmuCoreH/GPU", "PGXP", "false"))
            assertTrue(CoreRuntime.updateSetting("EmuCoreH", "Renderer", RendererDefaults.SOFTWARE.toString()))
            assertTrue(EmulatorBridge.startEmulation("", allowBiosBoot = true))
            EmulatorBridge.pause()
            val worker = Thread.getAllStackTraces().keys.single { it.name == "EmuCoreH-Frame" }
            // Terminate the paused owner unexpectedly, not through shutdown.
            // This reproduces the ownership boundary shared by fatal frame/audio errors.
            worker.interrupt()
            worker.join(5_000)
            assertFalse("interrupted paused worker did not stop", worker.isAlive)
            assertFalse(NativeApp.hasValidVm())
            val failure = EmulatorBridge.runtimeFailure.value
            assertNotNull("unexpected stop was not published to the UI bridge", failure)
            assertTrue(failure!!.detail.contains("interrupted unexpectedly"))
            assertTrue("failed runtime lost ownership", EmulatorBridge.isVmActive())
            assertTrue("stopped worker unexpectedly destroyed its native session",
                CoreRuntime.saveState(state.absolutePath))
            EmulatorBridge.isVmActive() // Read-only UI/metadata polling must not abandon ownership.
            EmulatorBridge.shutdown()
            assertFalse("bridge skipped cleanup after observing a stopped worker",
                CoreRuntime.saveState(state.absolutePath))
            assertFalse(EmulatorBridge.isVmActive())
            assertNull("shutdown retained the previous session error", EmulatorBridge.runtimeFailure.value)
            EmulatorBridge.shutdown() // Idempotent even after resources are gone.
            assertTrue(EmulatorBridge.startEmulation("", allowBiosBoot = true))
            assertNull("new session inherited an old fault", EmulatorBridge.runtimeFailure.value)
            assertTrue(EmulatorBridge.hasValidVm())
            // Early validation rejects a replacement without destroying the
            // previous owner. The bridge must not abandon that live session.
            assertTrue(CoreRuntime.updateSetting("Filenames", "BIOS", "missing-owned-fixture.bin"))
            assertTrue(CoreRuntime.updateSetting("Folders", "Bios", rom.absolutePath)) // File, not a search directory.
            assertTrue(CoreRuntime.updateSetting("EmuCoreH", "BiosSource", ""))
            assertFalse(EmulatorBridge.startEmulation("", allowBiosBoot = true))
            assertTrue(EmulatorBridge.hasValidVm())
            assertTrue(EmulatorBridge.isVmActive())
            EmulatorBridge.shutdown()
            assertFalse(CoreRuntime.saveState(state.absolutePath))
            assertNull("ordinary shutdown was reported as a failure", EmulatorBridge.runtimeFailure.value)
        } finally {
            EmulatorBridge.shutdown()
            CoreRuntime.shutdown()
            CoreRuntime.detachSurface()
            CoreRuntime.updateSetting("EmuCoreH", "Renderer", previousRenderer)
            CoreRuntime.settings.clear()
            CoreRuntime.settings.putAll(previousSettings)
            rom.delete()
            state.delete()
        }
    }
}
