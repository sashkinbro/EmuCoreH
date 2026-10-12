/*
	Copyright 2022 flyinghead

	This file is part of Flycast.

    Flycast is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.

    Flycast is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Flycast.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sbro.emucoreh.data.boxart

import android.graphics.Bitmap

/**
 * Kotlin port of Flycast's PVR texture reader (core/core/ui/boxart/pvrparser.h
 * plus the OpenGL conversion routines of core/core/rend/texconv.cpp). Used to
 * turn the 0GDTEX.PVR file found on Dreamcast discs into a Bitmap, exactly like
 * the offline box art scraper of the official emulator.
 */
object PvrDecoder {

    private const val VQ_CODEBOOK_SIZE = 256 * 8

    private const val PVR_SQUARE_TWIDDLED = 0x01
    private const val PVR_SQUARE_TWIDDLED_MIPMAPS = 0x02
    private const val PVR_VQ = 0x03
    private const val PVR_VQ_MIPMAPS = 0x04
    private const val PVR_RECTANGLE = 0x09
    private const val PVR_STRIDE = 0x0B
    private const val PVR_RECTANGLE_TWIDDLED = 0x0D
    private const val PVR_SQUARE_TWIDDLED_MIPMAPS_ALT = 0x12

    private val VQ_MIP_POINT = intArrayOf(
        VQ_CODEBOOK_SIZE + 0x00000,
        VQ_CODEBOOK_SIZE + 0x00001,
        VQ_CODEBOOK_SIZE + 0x00002,
        VQ_CODEBOOK_SIZE + 0x00006,
        VQ_CODEBOOK_SIZE + 0x00016,
        VQ_CODEBOOK_SIZE + 0x00056,
        VQ_CODEBOOK_SIZE + 0x00156,
        VQ_CODEBOOK_SIZE + 0x00556,
        VQ_CODEBOOK_SIZE + 0x01556,
        VQ_CODEBOOK_SIZE + 0x05556,
        VQ_CODEBOOK_SIZE + 0x15556
    )

    private val OTHER_MIP_POINT = intArrayOf(
        0x00003,
        0x00004,
        0x00008,
        0x00018,
        0x00058,
        0x00158,
        0x00558,
        0x01558,
        0x05558,
        0x15558,
        0x55558
    )

    /** Decodes PVR data (with or without the GBIX wrapper) into an ARGB bitmap. */
    fun decode(data: ByteArray): Bitmap? {
        val length = data.size
        if (length < 16) return null

        var p = 0
        if (length >= 8 && data[0] == 'G'.code.toByte() && data[1] == 'B'.code.toByte()
            && data[2] == 'I'.code.toByte() && data[3] == 'X'.code.toByte()
        ) {
            val indexSize = u32(data, 4)
            if (indexSize < 0 || indexSize > length - 8) return null
            p = 8 + indexSize
        }
        if (p + 4 > length || !hasAscii(data, p, "PVRT")) return null
        if (length - p < 16) return null

        p += 4
        p += 4 // size
        val pixelFormat = data[p++].toInt() and 0xFF
        val imageType = data[p++].toInt() and 0xFF
        p += 2
        val width = u16(data, p).also { p += 2 }
        val height = u16(data, p).also { p += 2 }
        if (width <= 0 || height <= 0 || width > 1024 || height > 1024) return null

        val unpacker = when (pixelFormat) {
            0 -> ::unpack1555
            1 -> ::unpack565
            2 -> ::unpack4444
            else -> return null
        }

        val twiddled = imageType == PVR_SQUARE_TWIDDLED ||
            imageType == PVR_SQUARE_TWIDDLED_MIPMAPS ||
            imageType == PVR_RECTANGLE_TWIDDLED ||
            imageType == PVR_SQUARE_TWIDDLED_MIPMAPS_ALT
        val vq = imageType == PVR_VQ || imageType == PVR_VQ_MIPMAPS
        val planar = imageType == PVR_RECTANGLE || imageType == PVR_STRIDE
        if (!twiddled && !vq && !planar) return null

        val codebookOffset = p
        var texU = 3
        while ((1 shl texU) < width) texU++
        when (imageType) {
            PVR_SQUARE_TWIDDLED_MIPMAPS_ALT -> p += OTHER_MIP_POINT[texU] * 2
            PVR_SQUARE_TWIDDLED_MIPMAPS -> p += (OTHER_MIP_POINT[texU] - 2) * 2
            PVR_VQ_MIPMAPS -> p += VQ_MIP_POINT[texU]
            PVR_VQ -> p += VQ_CODEBOOK_SIZE
        }

        var expectedSize = width * height
        expectedSize = if (vq) expectedSize / 4 else expectedSize * 2
        if (p < 0 || length - p < expectedSize) return null

        val pixels = IntArray(width * height)
        when {
            vq -> decodeVq(pixels, width, height, data, p, codebookOffset, unpacker)
            planar -> decodePlanar(pixels, width, height, data, p, unpacker)
            else -> decodeTwiddled(pixels, width, height, data, p, unpacker)
        }

        return runCatching {
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    private typealias Unpacker = (Int) -> Int

    private fun decodePlanar(
        pixels: IntArray,
        width: Int,
        height: Int,
        data: ByteArray,
        offset: Int,
        unpacker: Unpacker
    ) {
        val blocksPerLine = width / 4
        for (y in 0 until height) {
            var x = 0
            for (block in 0 until blocksPerLine) {
                val p = offset + (y * blocksPerLine + block) * 8
                val row = y * width + x
                pixels[row] = unpacker(u16(data, p))
                pixels[row + 1] = unpacker(u16(data, p + 2))
                pixels[row + 2] = unpacker(u16(data, p + 4))
                pixels[row + 3] = unpacker(u16(data, p + 6))
                x += 4
            }
        }
    }

    private fun decodeTwiddled(
        pixels: IntArray,
        width: Int,
        height: Int,
        data: ByteArray,
        offset: Int,
        unpacker: Unpacker
    ) {
        val bcx = bitscanrev(width)
        val bcy = bitscanrev(height)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val p = offset + (twop(x, y, bcx, bcy) / 4) * 8
                val row = y * width + x
                pixels[row] = unpacker(u16(data, p))
                pixels[row + width] = unpacker(u16(data, p + 2))
                pixels[row + 1] = unpacker(u16(data, p + 4))
                pixels[row + width + 1] = unpacker(u16(data, p + 6))
                x += 2
            }
            y += 2
        }
    }

    private fun decodeVq(
        pixels: IntArray,
        width: Int,
        height: Int,
        data: ByteArray,
        offset: Int,
        codebookOffset: Int,
        unpacker: Unpacker
    ) {
        val bcx = bitscanrev(width)
        val bcy = bitscanrev(height)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = data[offset + twop(x, y, bcx, bcy) / 4].toInt() and 0xFF
                val p = codebookOffset + index * 8
                val row = y * width + x
                pixels[row] = unpacker(u16(data, p))
                pixels[row + width] = unpacker(u16(data, p + 2))
                pixels[row + 1] = unpacker(u16(data, p + 4))
                pixels[row + width + 1] = unpacker(u16(data, p + 6))
                x += 2
            }
            y += 2
        }
    }

    private fun unpack1555(word: Int): Int {
        val r = (((word shr 10) and 0x1F) shl 3) or ((word shr 12) and 7)
        val g = (((word shr 5) and 0x1F) shl 3) or ((word shr 7) and 7)
        val b = ((word and 0x1F) shl 3) or ((word shr 2) and 7)
        val a = if (word and 0x8000 != 0) 0xFF else 0
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun unpack565(word: Int): Int {
        val r = (((word shr 11) and 0x1F) shl 3) or ((word shr 13) and 7)
        val g = (((word shr 5) and 0x3F) shl 2) or ((word shr 9) and 3)
        val b = ((word and 0x1F) shl 3) or ((word shr 2) and 7)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun unpack4444(word: Int): Int {
        val r = (((word shr 8) and 0xF) shl 4) or ((word shr 8) and 0xF)
        val g = (((word shr 4) and 0xF) shl 4) or ((word shr 4) and 0xF)
        val b = ((word and 0xF) shl 4) or (word and 0xF)
        val a = (((word shr 12) and 0xF) shl 4) or ((word shr 12) and 0xF)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun bitscanrev(value: Int): Int = 31 - Integer.numberOfLeadingZeros(value)

    private fun twop(x: Int, y: Int, bcx: Int, bcy: Int): Int =
        twiddleSlow(x, 0, 1024, 1 shl bcy) + twiddleSlow(0, y, 1 shl bcx, 1024)

    private fun twiddleSlow(x: Int, y: Int, xSize: Int, ySize: Int): Int {
        var xv = x
        var yv = y
        var xSz = xSize shr 1
        var ySz = ySize shr 1
        var rv = 0
        var sh = 0
        while (xSz != 0 || ySz != 0) {
            if (ySz != 0) {
                rv = rv or ((yv and 1) shl sh)
                ySz = ySz shr 1
                yv = yv shr 1
                sh++
            }
            if (xSz != 0) {
                rv = rv or ((xv and 1) shl sh)
                xSz = xSz shr 1
                xv = xv shr 1
                sh++
            }
        }
        return rv
    }

    private fun hasAscii(data: ByteArray, offset: Int, text: String): Boolean {
        if (offset < 0 || offset + text.length > data.size) return false
        for (i in text.indices) {
            if (data[offset + i] != text[i].code.toByte()) return false
        }
        return true
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}
