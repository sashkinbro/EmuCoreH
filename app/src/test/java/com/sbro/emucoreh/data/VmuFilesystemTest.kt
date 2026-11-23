package com.sbro.emucoreh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VmuFilesystemTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun zeroedImageReportsUnformattedWithAll200BlocksFree() {
        val vmu = tempFolder.newFile("vmu_save_A1.bin")
        vmu.writeBytes(ByteArray(VmuFilesystem.VMU_SIZE_BYTES))

        val summary = VmuFilesystem.inspectCard(vmu, tempFolder.newFolder("icons"))
        assertEquals("VMU A1", summary.portName)
        assertFalse(summary.isFormatted)
        assertEquals(200, summary.freeBlocks)
        assertEquals(200, summary.totalBlocks)
        assertTrue(summary.files.isEmpty())
    }

    @Test
    fun formatInitializesRootAndFatWith200FreeBlocks() {
        val vmu = tempFolder.newFile("vmu_save_B2.bin")
        vmu.writeBytes(ByteArray(VmuFilesystem.VMU_SIZE_BYTES))

        assertTrue(VmuFilesystem.formatCard(vmu, 1_700_000_000_000L))

        val summary = VmuFilesystem.inspectCard(vmu, tempFolder.newFolder("icons2"))
        assertEquals("VMU B2", summary.portName)
        assertTrue(summary.isFormatted)
        assertEquals(200, summary.freeBlocks)
        assertEquals(200, summary.totalBlocks)
        assertTrue(summary.files.isEmpty())
    }

    @Test
    fun parsesVmsEntryExtractsIconAndFreesBlocksOnDelete() {
        val vmu = tempFolder.newFile("vmu_save_A1.bin")
        assertTrue(VmuFilesystem.formatCard(vmu, 1_700_000_000_000L))

        val raw = vmu.readBytes()
        val fatOff = 254 * 512
        // Allocate blocks 10 -> 11 -> 12 (3 blocks)
        writeU16(raw, fatOff + 10 * 2, 11)
        writeU16(raw, fatOff + 11 * 2, 12)
        writeU16(raw, fatOff + 12 * 2, 0xFFFA)

        // Write directory entry in block 253 slot 0
        val dirOff = 253 * 512
        raw[dirOff] = 0x33
        raw[dirOff + 1] = 0x00
        writeU16(raw, dirOff + 2, 10)
        "SONIC_ADV001".toByteArray(Charsets.US_ASCII).copyInto(raw, dirOff + 4)
        // BCD timestamp 2026-05-12 14:30:00
        raw[dirOff + 0x10] = 0x20
        raw[dirOff + 0x11] = 0x26
        raw[dirOff + 0x12] = 0x05
        raw[dirOff + 0x13] = 0x12
        raw[dirOff + 0x14] = 0x14
        raw[dirOff + 0x15] = 0x30
        writeU16(raw, dirOff + 0x18, 3)
        writeU16(raw, dirOff + 0x1A, 0)

        // Write VMS header across blocks 10 & 11
        val b10 = 10 * 512
        "Sonic Save".toByteArray(Charsets.US_ASCII).copyInto(raw, b10)
        "Sonic Adventure File".toByteArray(Charsets.US_ASCII).copyInto(raw, b10 + 16)
        writeU16(raw, b10 + 0x40, 1) // 1 icon
        // Palette color #1 = opaque orange (A=15, R=15, G=5, B=2)
        writeU16(raw, b10 + 0x62, 0xFF52)
        // First pixel pair = 0x11 -> both pixels use palette #1
        raw[b10 + 0x80] = 0x11
        vmu.writeBytes(raw)

        val iconDir = tempFolder.newFolder("icons3")
        val before = VmuFilesystem.inspectCard(vmu, iconDir)
        assertTrue(before.isFormatted)
        assertEquals(197, before.freeBlocks)
        assertEquals(1, before.files.size)

        val item = before.files[0]
        assertEquals("SONIC_ADV001", item.fileName)
        assertEquals("Sonic Save", item.shortDesc)
        assertEquals(3, item.blocks)
        assertEquals("12/05/2026 14:30", item.timestamp)
        assertNotNull(item.iconPath)
        val pngFile = File(item.iconPath!!)
        assertTrue(pngFile.isFile && pngFile.length() > 60L)
        val pngHdr = pngFile.readBytes().copyOfRange(0, 8)
        assertEquals(0x89, pngHdr[0].toInt() and 0xFF)
        assertEquals('P'.code, pngHdr[1].toInt())
        assertEquals('N'.code, pngHdr[2].toInt())
        assertEquals('G'.code, pngHdr[3].toInt())

        // Delete the file and verify blocks 10, 11, 12 return to free pool
        assertTrue(VmuFilesystem.deleteFile(vmu, item))
        val after = VmuFilesystem.inspectCard(vmu, iconDir)
        assertEquals(200, after.freeBlocks)
        assertTrue(after.files.isEmpty())
    }

    @Test
    fun rejectsDreamcastBiosBlobsAsVmuCards() {
        val bootRom = tempFolder.newFile("dc_boot.bin")
        val bootHeader = byteArrayOf(0x53, 0x45, 0x47, 0x41, 0x20, 0x42, 0x4F, 0x4F, 0x54)
        bootRom.writeBytes(bootHeader + ByteArray(VmuFilesystem.VMU_SIZE_BYTES * 2 - bootHeader.size))
        val flashRom = tempFolder.newFile("dc_flash.bin")
        val flashPayload = ByteArray(VmuFilesystem.VMU_SIZE_BYTES) { 0x2A }
        flashRom.writeBytes(flashPayload)
        val validVmu = tempFolder.newFile("vmu_save_A1.bin")
        validVmu.writeBytes(ByteArray(VmuFilesystem.VMU_SIZE_BYTES))

        assertFalse(VmuFilesystem.isVmuImageFile(bootRom))
        assertFalse(VmuFilesystem.isVmuImageFile(flashRom))
        assertTrue(VmuFilesystem.isVmuImageFile(validVmu))

        assertFalse(VmuFilesystem.formatCard(bootRom, 1_700_000_000_000L))
        assertFalse(VmuFilesystem.formatCard(flashRom, 1_700_000_000_000L))
        assertTrue(bootRom.readBytes().copyOf(bootHeader.size).contentEquals(bootHeader))
        assertTrue(flashRom.readBytes().contentEquals(flashPayload))
    }

    private fun writeU16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
