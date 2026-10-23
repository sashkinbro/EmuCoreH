package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class CoreSaveStateTest {
    private val payload = byteArrayOf(1, 7, 3, 9)

    @Test fun legacyFlycastStatesRemainCompatible() {
        val encoded = CoreSaveState.wrap(payload, 0)
        assertEquals(1, encoded[4].toInt())
        assertArrayEquals(payload, CoreSaveState.unwrap(encoded, 0))
        assertArrayEquals(payload, CoreSaveState.unwrap(payload, 0))
        assertNull(CoreSaveState.unwrap(encoded, 1))
        assertNull(CoreSaveState.unwrap(payload, 1))
    }

    @Test fun segaStatesCannotBeLoadedByFlycast() {
        val encoded = CoreSaveState.wrap(payload, 1)
        assertEquals(2, encoded[4].toInt())
        assertArrayEquals(payload, CoreSaveState.unwrap(encoded, 1))
        assertNull(CoreSaveState.unwrap(encoded, 0))
    }

    @Test fun rejectsTruncatedAndUnknownContainers() {
        val encoded = CoreSaveState.wrap(payload, 1)
        assertNull(CoreSaveState.unwrap(encoded.copyOf(9), 1))
        encoded[4] = 99
        assertNull(CoreSaveState.unwrap(encoded, 1))
    }
}
