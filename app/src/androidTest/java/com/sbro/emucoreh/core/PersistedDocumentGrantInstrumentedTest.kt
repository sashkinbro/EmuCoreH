package com.sbro.emucoreh.core

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class PersistedDocumentGrantInstrumentedTest {
    @Test fun individualAndMalformedDocumentGrantsAreNotOpenedAsTrees() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (uri in listOf(
            "content://com.android.externalstorage.documents/document/primary%3Agame.chd",
            "content://com.android.providers.downloads.documents/document/42",
            "content://provider/tree",
            "file:///sdcard/game.chd"
        )) assertNull(uri, DocumentPathResolver.openPersistedTree(context, Uri.parse(uri)))
        assertNotNull(DocumentPathResolver.openPersistedTree(context,
            Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ASegaALL")))
    }
}
