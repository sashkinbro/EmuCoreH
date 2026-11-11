package com.sbro.emucoreh.core

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DreamcastBiosRequirementInstrumentedTest {
    @Test fun absentFlashOnlyAndWrongConsoleFirmwareNeverStartDreamcastOrBiosMenu() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "required-dc-bios-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val settings = CoreRuntime.settings.toMap()
        val options = CoreRuntime.launchCoreOptions
        try {
            CoreRuntime.shutdown()
            CoreRuntime.initialize(context)
            CoreRuntime.launchCoreOptions = mapOf(FlycastCoreOptions.HLE_BIOS_KEY to "enabled")
            val system = EmulatorStorage.flycastSystemDir(context)
            val dc = DreamcastBios.directory(system.path).apply { mkdirs() }
            fun rejected() {
                assertFalse("BIOS menu needs a real boot ROM", CoreRuntime.start("", true))
                assertFalse("Game cannot fall back to HLE", CoreRuntime.start(File(root, "owner.cdi").path, false))
                assertFalse(CoreRuntime.hasSession())
                assertFalse(CoreRuntime.isRunning())
            }
            rejected()
            File(dc, "dc_flash.bin").writeBytes(ByteArray(DreamcastBios.FLASH_ROM_SIZE.toInt()))
            rejected()
            File(dc, "dc_boot.bin").writeBytes(ByteArray(DreamcastBios.BOOT_ROM_SIZE.toInt()).apply {
                "SEGA NAOMI".toByteArray().copyInto(this)
            })
            rejected()
            File(dc, "dc_boot.bin").writeBytes(ByteArray(1024))
            rejected()
        } finally {
            CoreRuntime.shutdown()
            CoreRuntime.settings.clear()
            CoreRuntime.settings.putAll(settings)
            CoreRuntime.launchCoreOptions = options
            CoreRuntime.initialize(base)
            root.deleteRecursively()
        }
    }
}
