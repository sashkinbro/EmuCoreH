package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class CoreSaveStateTest {
    @Test fun rawSaturnStatesCannotEnterFlycastAndWrappedStatesKeepCoreIdentity() {
        val saturn = "YSS".toByteArray() + byteArrayOf(1, 2, 0, 0, 0, 42)
        assertNull(CoreSaveState.unwrap(saturn, 0))
        val encoded = CoreSaveState.wrap(saturn, 3)
        assertArrayEquals(saturn, CoreSaveState.unwrap(encoded, 3))
        for (core in 0..2) assertNull(CoreSaveState.unwrap(encoded, core))
        val mednafen = ByteArray(64).also { "MDFNSVST".toByteArray().copyInto(it) }
        assertNull(CoreSaveState.unwrap(mednafen, 0))
        assertArrayEquals(mednafen, CoreSaveState.unwrap(CoreSaveState.wrap(mednafen, 3), 3))
    }
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
