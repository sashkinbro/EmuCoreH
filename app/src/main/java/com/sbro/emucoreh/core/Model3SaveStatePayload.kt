// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class Model3StateMetadata(val setName: String, val payloadBytes: Int)

/** Bounded streaming validation of the native M3RETRO1 envelope, including legacy block framing. */
internal object Model3SaveStatePayload {
    const val MAX_BYTES = 64 * 1024 * 1024
    private val magic = "M3RETRO1".toByteArray(Charsets.US_ASCII)
    fun hasMagic(bytes: ByteArray): Boolean = bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }

    fun read(input: InputStream, expectedSet: String? = null): Model3StateMetadata? = runCatching {
        val header = input.readExactly(80) ?: return null
        if (!hasMagic(header)) return null
        val words = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (words.getInt(8) != 1) return null
        val size = words.getInt(12)
        if (size !in 4..MAX_BYTES - 80) return null
        val nameEnd = (20 until 80).firstOrNull { header[it] == 0.toByte() } ?: return null
        if (nameEnd == 20 || (nameEnd until 80).any { header[it] != 0.toByte() }) return null
        val set = header.copyOfRange(20, nameEnd).toString(Charsets.US_ASCII)
        if (!set.matches(Regex("[a-z0-9_]+")) || Model3Romsets.find("$set.zip") == null ||
            (expectedSet != null && expectedSet != set)) return null
        val payload = PayloadReader(input, size)
        val gear = payload.word() ?: return null
        if (gear !in 0..4) return null
        var foundModel = false
        var blocks = 0
        while (payload.remaining > 0) {
            if (++blocks > 256) return null
            val blockLength = payload.word() ?: return null
            val nameLength = payload.word() ?: return null
            val commentLength = payload.word() ?: return null
            if (nameLength !in 1..1025 || commentLength !in 1..1025 || blockLength < 12 + nameLength + commentLength ||
                blockLength - 12 > payload.remaining) return null
            val name = payload.bytes(nameLength) ?: return null
            val comment = payload.bytes(commentLength) ?: return null
            if (name.last() != 0.toByte() || comment.last() != 0.toByte()) return null
            if (name.toString(Charsets.US_ASCII).removeSuffix("\u0000") == "Model 3") foundModel = true
            if (!payload.skip(blockLength - 12 - nameLength - commentLength)) return null
        }
        if (!foundModel || payload.hash != words.getInt(16)) return null
        // Native serializers may pad a fixed buffer with zeros, bounded by the same total limit.
        var total = 80 + size
        while (true) {
            val byte = input.read()
            if (byte < 0) break
            if (++total > MAX_BYTES || byte != 0) return null
        }
        Model3StateMetadata(set, size)
    }.getOrNull()

    private fun InputStream.readExactly(size: Int): ByteArray? {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(bytes, offset, size - offset)
            if (count < 0) return null
            if (count == 0) { val byte = read(); if (byte < 0) return null; bytes[offset++] = byte.toByte() }
            else offset += count
        }
        return bytes
    }

    private class PayloadReader(private val input: InputStream, size: Int) {
        var remaining = size; private set
        var hash = 0x811c9dc5.toInt(); private set
        fun bytes(size: Int): ByteArray? {
            if (size !in 0..remaining) return null
            val result = input.readExactly(size) ?: return null
            result.forEach { hash = (hash xor (it.toInt() and 255)) * 16777619 }
            remaining -= size
            return result
        }
        fun word(): Int? = bytes(4)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int }
        fun skip(size: Int): Boolean {
            if (size !in 0..remaining) return false
            var left = size
            while (left > 0) { val count = minOf(left, 65536); if (bytes(count) == null) return false; left -= count }
            return true
        }
    }
}
