package com.sbro.emucoreh.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeBackgroundRestoreInstrumentedTest {
    @Test fun restoreReplacesBackgroundAndInvalidRestorePreservesIt() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "background-restore-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        fun png(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            return try {
                bitmap.eraseColor(color)
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
            } finally { bitmap.recycle() }
        }
        try {
            val repository = HomeBackgroundRepository(isolated)
            val original = png(0xff123456.toInt())
            val replacement = png(0xffabcdef.toInt())
            repository.restoreFromBackup(HomeBackgroundType.IMAGE, original.inputStream()).getOrThrow()
            assertTrue(repository.restoreFromBackup(HomeBackgroundType.IMAGE, replacement.inputStream()).isSuccess)
            assertArrayEquals(replacement, repository.existingFile(HomeBackgroundType.IMAGE)!!.readBytes())
            assertTrue(repository.restoreFromBackup(HomeBackgroundType.IMAGE, byteArrayOf(1, 2, 3).inputStream()).isFailure)
            assertEquals(HomeBackgroundType.IMAGE, repository.installedBackground()!!.first)
            assertArrayEquals(replacement, repository.existingFile(HomeBackgroundType.IMAGE)!!.readBytes())
            val gif = Base64.decode("R0lGODlhAgACAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAAgACAAAIBgABCAQQEAAh+QQBCgABACwAAAAAAgACAIEAAP8AAAAAAAAAAAAIBgABCAQQEAA7", Base64.DEFAULT)
            repository.restoreFromBackup(HomeBackgroundType.GIF, gif.inputStream()).getOrThrow()
            assertEquals(HomeBackgroundType.GIF, repository.installedBackground()!!.first)
            assertArrayEquals(gif, repository.existingFile(HomeBackgroundType.GIF)!!.readBytes())
            assertEquals(null, repository.existingFile(HomeBackgroundType.IMAGE))
        } finally { root.deleteRecursively() }
    }
}
