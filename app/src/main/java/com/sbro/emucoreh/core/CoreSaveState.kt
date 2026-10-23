package com.sbro.emucoreh.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Flycast keeps its v1 format; additional cores carry an explicit identity. */
internal object CoreSaveState {
    private const val MAGIC = 0x54534345

    fun wrap(payload: ByteArray, coreId: Int): ByteArray =
        ByteBuffer.allocate(payload.size + if (coreId == 0) 8 else 12)
            .order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(MAGIC)
                putInt(if (coreId == 0) 1 else 2)
                if (coreId != 0) putInt(coreId)
                put(payload)
            }.array()

    fun unwrap(bytes: ByteArray, coreId: Int): ByteArray? {
        if (bytes.size < 4 || ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int != MAGIC) {
            return bytes.takeIf { coreId == 0 }
        }
        if (bytes.size <= 8) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.int
        val offset = when (buffer.int) {
            1 -> if (coreId == 0) 8 else return null
            2 -> if (bytes.size > 12 && buffer.int == coreId) 12 else return null
            else -> return null
        }
        return bytes.copyOfRange(offset, bytes.size)
    }
}
