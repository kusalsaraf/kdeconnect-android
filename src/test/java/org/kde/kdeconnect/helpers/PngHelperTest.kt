/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.helpers

import org.junit.Assert
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.imageio.ImageIO

class PngHelperTest {
    @Test
    fun removesColorSpaceChunks() {
        val input = png(
            ihdr(), chunk("sRGB", byteArrayOf(0)), chunk("gAMA", int(45455)), chunk("cHRM", ByteArray(32)),
            chunk("iCCP", "icc".toByteArray() + byteArrayOf(0, 0)), chunk("cICP", byteArrayOf(1, 13, 0, 1)),
            chunk("sBIT", byteArrayOf(8, 8, 8, 8)), idat(), iend(),
        )

        val output = PngHelper.stripColorSpaceChunks(input)

        Assert.assertEquals(listOf("IHDR", "sBIT", "IDAT", "IEND"), chunkTypes(output))
    }

    @Test
    fun strippedPngStillDecodesToSamePixels() {
        val input = png(ihdr(), chunk("sRGB", byteArrayOf(0)), idat(), iend())

        val output = PngHelper.stripColorSpaceChunks(input)

        val before = ImageIO.read(ByteArrayInputStream(input))
        val after = ImageIO.read(ByteArrayInputStream(output))
        Assert.assertNotNull("stripped PNG must still decode", after)
        Assert.assertEquals(before.getRGB(0, 0), after.getRGB(0, 0))
    }

    @Test
    fun pngWithoutColorSpaceChunksIsUnchanged() {
        val input = png(ihdr(), idat(), iend())

        Assert.assertArrayEquals(input, PngHelper.stripColorSpaceChunks(input))
    }

    @Test
    fun nonPngInputIsReturnedUnchanged() {
        val empty = byteArrayOf()
        val text = "not a png".toByteArray()

        Assert.assertArrayEquals(empty, PngHelper.stripColorSpaceChunks(empty))
        Assert.assertArrayEquals(text, PngHelper.stripColorSpaceChunks(text))
    }

    @Test
    fun truncatedPngIsReturnedUnchanged() {
        val full = png(ihdr(), chunk("sRGB", byteArrayOf(0)), idat(), iend())
        val idatStart = full.size - iend().size - idat().size
        val truncated = full.copyOf(idatStart + 10) // cut in the middle of IDAT

        Assert.assertArrayEquals(truncated, PngHelper.stripColorSpaceChunks(truncated))
    }

    // --- PNG construction helpers (1x1 RGBA image) ---

    private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun png(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(signature)
        chunks.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun int(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        return int(data.size) + typeBytes + data + int(crc.value.toInt())
    }

    // width=1, height=1, bit depth 8, colour type 6 (RGBA), compression 0, filter 0, interlace 0
    private fun ihdr(): ByteArray = chunk("IHDR", int(1) + int(1) + byteArrayOf(8, 6, 0, 0, 0))

    private fun idat(): ByteArray {
        val scanline = byteArrayOf(0, 0x33, 0x66, 0x99.toByte(), 0xFF.toByte()) // filter byte + one RGBA pixel
        val deflater = Deflater().apply { setInput(scanline); finish() }
        val buffer = ByteArray(64)
        val length = deflater.deflate(buffer)
        deflater.end()
        return chunk("IDAT", buffer.copyOf(length))
    }

    private fun iend(): ByteArray = chunk("IEND", byteArrayOf())

    private fun chunkTypes(png: ByteArray): List<String> {
        val types = mutableListOf<String>()
        var offset = signature.size
        while (offset + 8 <= png.size) {
            val length = ByteBuffer.wrap(png, offset, 4).int
            types += String(png, offset + 4, 4, Charsets.US_ASCII)
            offset += 12 + length
        }
        return types
    }
}
