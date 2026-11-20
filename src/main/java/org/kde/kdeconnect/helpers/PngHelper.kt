/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.helpers

import java.io.ByteArrayOutputStream

object PngHelper {
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    // Chunk header (length + type) plus trailing CRC
    private const val CHUNK_OVERHEAD = 12

    private val COLOR_SPACE_CHUNKS = setOf("iCCP", "sRGB", "gAMA", "cHRM", "cICP")

    /**
     * Returns a copy of [png] without colour-space chunks (iCCP, sRGB, gAMA, cHRM, cICP).
     *
     * KDE Connect for macOS built against Qt 6.11 crashes in QImage::toCGImage() when a
     * notification icon carries colour-space information, which Android's PNG encoder always
     * adds. Untagged PNGs are treated as sRGB by decoders, so sRGB icons render the same.
     *
     * Input that is not a well-formed PNG is returned unchanged.
     */
    fun stripColorSpaceChunks(png: ByteArray): ByteArray {
        if (png.size < PNG_SIGNATURE.size || !png.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
            return png
        }
        val out = ByteArrayOutputStream(png.size)
        out.write(PNG_SIGNATURE)
        var offset = PNG_SIGNATURE.size
        while (offset < png.size) {
            if (offset + 8 > png.size) {
                return png
            }
            val length = readInt(png, offset)
            val end = offset.toLong() + CHUNK_OVERHEAD + length
            if (length < 0 || end > png.size) {
                return png
            }
            val type = String(png, offset + 4, 4, Charsets.US_ASCII)
            if (type !in COLOR_SPACE_CHUNKS) {
                out.write(png, offset, end.toInt() - offset)
            }
            offset = end.toInt()
        }
        return out.toByteArray()
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
