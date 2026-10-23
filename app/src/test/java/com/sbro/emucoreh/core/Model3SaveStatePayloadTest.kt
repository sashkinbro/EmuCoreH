// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

/** Authored format fixture, containing no game ROM, BIOS or emulator memory. */
internal fun model3PayloadFixture(set: String = "srally2"): ByteArray {
    fun block(name: String): ByteArray {
        val bytes = (name + "\u0000").toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(12 + bytes.size + 1).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(12 + bytes.size + 1).putInt(bytes.size).putInt(1).put(bytes).put(0.toByte()).array()
    }
    val payload = byteArrayOf(1, 0, 0, 0) + block("Supermodel Save State") + block("Model 3")
    var hash = 0x811c9dc5.toInt()
    payload.forEach { hash = (hash xor (it.toInt() and 255)) * 16777619 }
    return ByteBuffer.allocate(80 + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("M3RETRO1".toByteArray(Charsets.US_ASCII)); putInt(1); putInt(payload.size); putInt(hash)
        put(set.toByteArray(Charsets.US_ASCII)); position(80); put(payload)
    }.array()
}

class Model3SaveStatePayloadTest {
    @Test fun validatesVersionSetChecksumAndShortReads() {
        val bytes = model3PayloadFixture()
        val source = object : ByteArrayInputStream(bytes) {
            override fun read(data: ByteArray, offset: Int, length: Int) = super.read(data, offset, minOf(length, 1))
        }
        assertEquals("srally2", Model3SaveStatePayload.read(source)?.setName)
        assertNotNull(Model3SaveStatePayload.read(bytes.inputStream(), "srally2"))
        assertNull(Model3SaveStatePayload.read(bytes.inputStream(), "daytona2"))
        assertNull(Model3SaveStatePayload.read(model3PayloadFixture("unknown").inputStream()))
        listOf(0, 8, 12, 16, 80, bytes.lastIndex).forEach { position ->
            val corrupt = bytes.copyOf().apply { this[position] = (this[position].toInt() xor 1).toByte() }
            assertNull("corruption at $position", Model3SaveStatePayload.read(corrupt.inputStream()))
        }
        assertNull(Model3SaveStatePayload.read(bytes.copyOf(79).inputStream()))
        assertNull(Model3SaveStatePayload.read(bytes.copyOf(bytes.size - 1).inputStream()))
        val oversized = bytes.copyOf().apply { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(12, Int.MAX_VALUE) }
        assertNull(Model3SaveStatePayload.read(oversized.inputStream()))
    }

    @Test fun rejectsCrossCoreContainersAndRecognizedRawModel3PayloadInFlycast() {
        val payload = model3PayloadFixture()
        val state = CoreSaveState.wrap(payload, 2)
        assertArrayEquals(payload, CoreSaveState.unwrap(state, 2))
        assertNull(CoreSaveState.unwrap(state, 0)); assertNull(CoreSaveState.unwrap(state, 1))
        assertNull(CoreSaveState.unwrap(payload, 0))
        assertNull(CoreSaveState.unwrap(CoreSaveState.wrap(byteArrayOf(1, 2), 2), 2))
    }
}
