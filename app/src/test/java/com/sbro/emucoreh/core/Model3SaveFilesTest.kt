// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files

class Model3SaveFilesTest {
    @Test fun allowsOnlyKnownSetNvramAndRejectsFirmwareTemporaryFilesAndTraversal() {
        listOf("srally2/nvram.bin", "daytona2/nvram.bin", "dayto2pe/nvram.bin").forEach { assertTrue(it, Model3SaveFiles.isBackupPath(it)) }
        listOf("unknown/nvram.bin", "../nvram.bin", "srally2/../nvram.bin", "srally2/bios.bin",
            "srally2/serialize.tmp", "srally2/nvram.tmp", "srally2/game.zip", "srally2/Games.xml", "srally2\\nvram.bin",
            "srally2/nvram.bin:stream", "/srally2/nvram.bin").forEach { assertFalse(it, Model3SaveFiles.isBackupPath(it)) }
    }

    @Test fun backupSelectionAndAtomicBoundedRestoreKeepExistingNvramOnFailure() {
        val root = Files.createTempDirectory("model3-nvram").toFile()
        try {
            val nvram = File(root, "srally2/nvram.bin").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(7)) }
            File(root, "srally2/nvram.tmp").writeText("temporary")
            File(root, "srally2/bios.bin").writeText("not a save")
            File(root, "unknown/nvram.bin").apply { parentFile!!.mkdirs(); writeText("unknown") }
            assertEquals(listOf("srally2/nvram.bin"), Model3SaveFiles.backupFiles(root).map { it.first })
            Model3SaveFiles.restore(byteArrayOf(9, 8).inputStream(), root, "srally2/nvram.bin")
            assertArrayEquals(byteArrayOf(9, 8), nvram.readBytes())
            assertTrue(runCatching { Model3SaveFiles.restore(byteArrayOf(1).inputStream(), root, "../nvram.bin") }.isFailure)
            assertTrue(runCatching { Model3SaveFiles.restore(byteArrayOf().inputStream(), root, "srally2/nvram.bin") }.isFailure)
            val oversized = object : InputStream() {
                private var remaining = Model3SaveFiles.MAX_NVRAM_BYTES + 1
                override fun read(): Int = if (remaining-- > 0) 0 else -1
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (remaining <= 0) return -1
                    val count = minOf(length.toLong(), remaining).toInt()
                    bytes.fill(0, offset, offset + count); remaining -= count; return count
                }
            }
            assertTrue(runCatching { Model3SaveFiles.restore(oversized, root, "srally2/nvram.bin") }.isFailure)
            assertArrayEquals(byteArrayOf(9, 8), nvram.readBytes())
            assertFalse(nvram.parentFile!!.listFiles()!!.any { it.name.endsWith(".restoring") })
        } finally { root.deleteRecursively() }
    }
}
