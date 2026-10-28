package com.sbro.emucoreh.core

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuDriverManagerInstrumentedTest {
    @Test fun failedReplacementAndInvalidNamesPreserveExistingFiles() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "gpu-driver-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
        }
        val manager = GpuDriverManager(isolated)
        val archive = File(root, "driver.zip")
        fun writeArchive(payload: ByteArray) {
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("libvulkan.so"))
                zip.write(payload)
                zip.closeEntry()
            }
        }
        try {
            val original = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1)
            writeArchive(original)
            val name = manager.installFromArchive(archive)
            val installed = File(requireNotNull(manager.readMainLibraryPath(name)))
            assertTrue(installed.isFile)
            writeArchive("not a driver".toByteArray())
            assertTrue(runCatching { manager.installFromArchive(archive) }.isFailure)
            assertTrue("Failed replacement must preserve the installed driver", installed.isFile)
            assertTrue(installed.readBytes().contentEquals(original))

            val sentinel = File(root, "sentinel").apply { mkdirs(); resolve("data").writeText("keep") }
            assertTrue(runCatching { manager.remove("../sentinel") }.isFailure)
            assertEquals("keep", sentinel.resolve("data").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
