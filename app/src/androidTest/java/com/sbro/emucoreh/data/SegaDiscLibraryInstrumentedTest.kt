package com.sbro.emucoreh.data

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.core.NativeCoreBridge
import android.provider.DocumentsContract
import android.system.Os
import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SegaDiscLibraryInstrumentedTest {
    @Test fun ownerSegaCdDiscsRemainVisibleAfterCachedSafScan() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ASega")
        assumeTrue("Owner-authorized Sega tree required", context.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission
        })
        val discUri = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:Sega/Mortal Kombat (USA).chd")
        context.contentResolver.openFileDescriptor(discUri, "r")!!.use { descriptor ->
            val bytes = ByteArray(8)
            Os.pread(descriptor.fileDescriptor, bytes, 0, 8, 0)
            val proc = "/proc/self/fd/${descriptor.fd}"
            val reopen = runCatching { java.io.File(proc).inputStream().use { it.read() } }
            val probe = NativeCoreBridge().probeSegaDiscDescriptor(descriptor.fd)
            Log.i("SegaDiscLibraryQA", "magic=${bytes.toString(Charsets.US_ASCII)} reopen=$reopen probe=$probe")
            assertTrue("CHD SAF descriptor must identify Sega CD; magic=${bytes.toString(Charsets.US_ASCII)} reopen=$reopen probe=$probe", probe > 0)
        }
        val repository = GameRepository()
        val games = repository.scanDirectoryFromUri(tree, context)
        val discs = games.filter { it.fileName.endsWith(".chd", true) }
        assertTrue("Owner Sega CD disc fixtures must be discovered", discs.isNotEmpty())
        discs.forEach { assertEquals(it.fileName, EmulationPlatform.SEGA_CD.name, it.platform) }
        val cached = repository.scanDirectoryFromUri(tree, context, games.associateBy { it.path })
        assertEquals(discs.map { it.path }.toSet(), cached.filter { it.platform == "SEGA_CD" }.map { it.path }.toSet())
    }
}
