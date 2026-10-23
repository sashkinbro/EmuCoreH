// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.data

import com.sbro.emucoreh.core.CoreSaveState
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.core.model3PayloadFixture
import com.sbro.emucoreh.core.Model3ContentIdentity
import com.sbro.emucoreh.core.Model3ProgramCrcs
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class Model3SaveStateImportTest {
    @Test fun verifiedProgramRevisionKeepsImportsSeparateFromTheOldArchiveName() {
        val definitions = Model3ProgramCrcs.fingerprints
        val actual = definitions.single { it.name == "scudau" }
        val resolved = Model3ContentIdentity.identify("scud", actual.effective, definitions) { null }
        assertEquals("scudau", resolved)
        val state = CoreSaveState.wrap(model3PayloadFixture("scudau"), 2)
        assertNotNull(CoreSaveState.unwrap(state, 2, resolved))
        assertNull(CoreSaveState.unwrap(state, 2, "scud"))
        assertNull(CoreSaveState.unwrap(state, 1))
    }

    @Test fun readsValidatedNativeSetAndKeepsCoreNamespacesSeparate() {
        val state = CoreSaveState.wrap(model3PayloadFixture(), 2)
        val header = readSaveStateImportHeader(state.inputStream())!!
        assertEquals(2, header.coreId)
        assertEquals("srally2", header.model3Set)
        assertEquals(EmulationPlatform.MODEL_3, header.platform)
        assertTrue(isSaveStateImportCompatible(header, "M3-ABCD123456789012.01.rstate", null))
        assertTrue(isSaveStateImportCompatible(header, "M3-SRALLY2.01.rstate", EmulationPlatform.MODEL_3))
        assertFalse(isSaveStateImportCompatible(header, "M3-DAYTONA2.01.rstate", EmulationPlatform.MODEL_3))
        assertFalse(isSaveStateImportCompatible(header, "JG-GENESIS-ABCD123456789012.01.rstate", null))
        assertFalse(isSaveStateImportCompatible(header, "M3-ABCD123456789012.01.rstate", EmulationPlatform.GENESIS))
        assertTrue(isSaveStateImportCompatible(header, "renamed.rstate", EmulationPlatform.MODEL_3))
        assertFalse(isSaveStateImportCompatible(header, "renamed.rstate", null))
        assertEquals("M3-ABCD123456789012", parseExternalSaveStateName("M3-ABCD123456789012.01.rstate")!!.serial)
        state[state.lastIndex] = (state.last().toInt() xor 1).toByte()
        assertNull(readSaveStateImportHeader(state.inputStream()))
        val flycast = SaveStateImportHeader(1, 0)
        assertFalse(isSaveStateImportCompatible(flycast, "M3-ABCD123456789012.01.rstate", null))
    }

    @Test fun fallbackMatchesPayloadSetWhenPathIdentityDiffersFromGameSerial() {
        val root = Files.createTempDirectory("model3-slots").toFile()
        try {
            val correct = File(root, "M3-ABCD123456789012.01.rstate").apply { writeBytes(CoreSaveState.wrap(model3PayloadFixture("srally2"), 2)) }
            val wrong = File(root, "M3-EEEE123456789012.01.rstate").apply { writeBytes(CoreSaveState.wrap(model3PayloadFixture("daytona2"), 2)) }
            assertEquals(correct, findFallbackSaveStateFile(arrayOf(correct, wrong), "M3-SRALLY2", 1))
            assertNull(findFallbackSaveStateFile(arrayOf(correct, wrong), "M3-SRALLY2", 2))
        } finally { root.deleteRecursively() }
    }
}
