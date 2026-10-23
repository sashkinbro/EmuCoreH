package com.sbro.emucoreh.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucoreh.data.SaveStateRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SaveStatePreviewLifecycleInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun committedOverwriteWithNoSurfaceCannotReuseOldRedPreview() = runBlocking {
        withState { state, preview ->
            assertIdleWithoutSurface()
            val oldState = state.readBytes()
            val repository = SaveStateRepository(context)
            assertEquals(preview.path, repository.getPreviewImagePath(state))
            val committed = "new committed state".toByteArray()
            state.writeBytes(committed)
            assertFalse(oldState.contentEquals(state.readBytes()))
            EmulatorBridge.refreshSaveStatePreviewAfterSave(true, state.path, null)
            assertFalse("No old screenshot may survive the committed overwrite", preview.exists())
            assertNull(repository.getPreviewImagePath(state))
            assertArrayEquals(committed, state.readBytes())
        }
    }

    @Test fun failedStateSaveKeepsInstalledStateAndPreview() = runBlocking {
        withState { state, preview ->
            val oldState = state.readBytes()
            val oldPreview = preview.readBytes()
            EmulatorBridge.refreshSaveStatePreviewAfterSave(false, state.path, null)
            assertArrayEquals(oldState, state.readBytes())
            assertArrayEquals(oldPreview, preview.readBytes())
        }
    }

    @Test fun successfulSourceCaptureAtomicallyInstallsNewPreview() = runBlocking {
        withState { state, preview ->
            state.writeText("new committed state")
            EmulatorBridge.refreshSaveStatePreviewAfterSave(true, state.path, intArrayOf(1, 1, Color.GREEN))
            val image = requireNotNull(BitmapFactory.decodeFile(preview.path))
            try { assertEquals(Color.GREEN, image.getPixel(0, 0)) }
            finally { image.recycle() }
            assertTrue(state.parentFile!!.listFiles()!!.none { it.name.startsWith(".save-preview-") })
        }
    }

    private fun assertIdleWithoutSurface() {
        assertFalse("Run this no-ROM test with the VM stopped", CoreRuntime.hasSession())
        val surface = EmulatorBridge::class.java.getDeclaredField("lastSurface").apply { isAccessible = true }
        assertNull("Capture must take the actual no-surface path", surface.get(EmulatorBridge))
    }

    private suspend fun withState(block: suspend (File, File) -> Unit) {
        val root = File(context.cacheDir, "qa-preview-lifecycle-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val state = File(root, "M3-0123456789ABCDEF.01.rstate").apply { writeText("old installed state") }
            val preview = File("${state.path}.png")
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).let { image ->
                try {
                    image.eraseColor(Color.RED)
                    preview.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { image.recycle() }
            }
            block(state, preview)
        } finally { root.deleteRecursively() }
    }
}
