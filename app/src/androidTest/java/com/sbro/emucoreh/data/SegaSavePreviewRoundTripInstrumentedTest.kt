package com.sbro.emucoreh.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucoreh.core.CoreSaveState
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class SegaSavePreviewRoundTripInstrumentedTest {
    @Test fun exportedSaveRestoresItsPreviewAndRejectsInvalidSidecarWithoutRejectingState() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "sega-preview-qa-${UUID.randomUUID()}"
        val root = File(app.cacheDir, id).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$id-$name", mode)
        }
        try {
            val slots = File(root, "slots").apply { mkdirs() }
            val repository = SaveStateRepository(context, slots)
            val state = File(slots, "JG-GENESIS-0123456789ABCDEF.10.rstate").apply {
                writeBytes(CoreSaveState.wrap("JGLRSTA1".toByteArray() + byteArrayOf(0, 42), 1))
            }
            val sidecar = File("${state.path}.png")
            Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).let { image ->
                try { sidecar.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { image.recycle() }
            }
            val bytes = state.readBytes()
            val pixels = sidecar.readBytes()
            val archive = File(root, "states.zip")
            assertTrue(repository.backupEntries(repository.listEntries(), Uri.fromFile(archive)))
            ZipFile(archive).use { zip -> assertNotNull(zip.getEntry("slots/${state.name}.png")) }
            assertTrue(state.delete())
            assertTrue(sidecar.delete())
            val preview = repository.analyzeImport(Uri.fromFile(archive))
            assertEquals(1, repository.importStates(preview).importedCount)
            assertArrayEquals(bytes, state.readBytes())
            assertArrayEquals(pixels, sidecar.readBytes())
            assertEquals(sidecar.path, repository.getPreviewImagePath(repository.listEntries().single()))
            val collision = repository.analyzeImport(Uri.fromFile(archive))
            assertEquals(1, collision.candidates.single().targetSlot)
            val renamed = File(slots, collision.candidates.single().targetFileName)
            assertEquals(1, repository.importStates(collision).importedCount)
            assertArrayEquals(bytes, renamed.readBytes())
            assertArrayEquals(pixels, File("${renamed.path}.png").readBytes())
            assertFalse(copySaveStatePreview(byteArrayOf(1, 2, 3).inputStream(), sidecar))
            assertArrayEquals("Invalid incoming preview must preserve the installed preview", pixels, sidecar.readBytes())
            val oversized = object : java.io.InputStream() {
                private var remaining = MAX_SAVE_PREVIEW_BYTES + 1
                override fun read(): Int = if (remaining-- > 0) 0 else -1
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (remaining <= 0) return -1
                    val count = minOf(remaining, length.toLong()).toInt()
                    java.util.Arrays.fill(buffer, offset, offset + count, 0.toByte())
                    remaining -= count
                    return count
                }
            }
            assertFalse(copySaveStatePreview(oversized, sidecar))
            assertArrayEquals(pixels, sidecar.readBytes())
        } finally { root.deleteRecursively() }
    }
}
