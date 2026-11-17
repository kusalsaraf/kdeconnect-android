/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.helpers

import org.junit.Assert
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class JpegHelperTest {
    @Test
    fun removesIccProfileSegments() {
        val jpeg = jpeg()
        val withIcc = insertAfterApp0(jpeg, app2("ICC_PROFILE\u0000".toByteArray() + byteArrayOf(1, 1) + ByteArray(456)))

        Assert.assertArrayEquals(jpeg, JpegHelper.stripIccProfile(withIcc))
    }

    @Test
    fun keepsOtherApp2Segments() {
        val withMpf = insertAfterApp0(jpeg(), app2("MPF\u0000".toByteArray() + ByteArray(20)))

        Assert.assertArrayEquals(withMpf, JpegHelper.stripIccProfile(withMpf))
    }

    @Test
    fun jpegWithoutIccIsUnchanged() {
        val jpeg = jpeg()

        Assert.assertArrayEquals(jpeg, JpegHelper.stripIccProfile(jpeg))
    }

    @Test
    fun nonJpegInputIsReturnedUnchanged() {
        val empty = byteArrayOf()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        Assert.assertArrayEquals(empty, JpegHelper.stripIccProfile(empty))
        Assert.assertArrayEquals(png, JpegHelper.stripIccProfile(png))
    }

    @Test
    fun truncatedJpegIsReturnedUnchanged() {
        val withIcc = insertAfterApp0(jpeg(), app2("ICC_PROFILE\u0000".toByteArray() + byteArrayOf(1, 1) + ByteArray(456)))
        val truncated = withIcc.copyOf(30) // cut inside the ICC segment, before the image data

        Assert.assertArrayEquals(truncated, JpegHelper.stripIccProfile(truncated))
    }

    // --- JPEG construction helpers ---

    private fun jpeg(): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, 0x336699) }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }

    private fun app2(data: ByteArray): ByteArray {
        val length = data.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE2.toByte(), (length shr 8).toByte(), length.toByte()) + data
    }

    // ImageIO writes SOI followed by an APP0 (JFIF) segment
    private fun insertAfterApp0(jpeg: ByteArray, segment: ByteArray): ByteArray {
        val app0Length = ((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF)
        val position = 4 + app0Length
        return jpeg.copyOfRange(0, position) + segment + jpeg.copyOfRange(position, jpeg.size)
    }
}
