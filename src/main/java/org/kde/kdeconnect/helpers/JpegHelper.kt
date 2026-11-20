/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.helpers

import java.io.ByteArrayOutputStream

object JpegHelper {
    private const val MARKER_PREFIX = 0xFF
    private const val START_OF_IMAGE = 0xD8
    private const val START_OF_SCAN = 0xDA
    private const val APP2 = 0xE2
    private val ICC_PROFILE_ID = "ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * Returns a copy of [jpeg] without its embedded ICC profile (APP2 "ICC_PROFILE" segments).
     *
     * KDE Connect for macOS built against Qt 6.11 crashes in QImage::toCGImage() on the ICC
     * profile that Android's JPEG encoder embeds. Untagged JPEGs are treated as sRGB by decoders,
     * so sRGB images render the same.
     *
     * Input that is not a well-formed JPEG is returned unchanged.
     */
    fun stripIccProfile(jpeg: ByteArray): ByteArray {
        if (jpeg.size < 4 || byteAt(jpeg, 0) != MARKER_PREFIX || byteAt(jpeg, 1) != START_OF_IMAGE) {
            return jpeg
        }
        val out = ByteArrayOutputStream(jpeg.size)
        out.write(jpeg, 0, 2)
        var offset = 2
        while (true) {
            if (offset + 4 > jpeg.size || byteAt(jpeg, offset) != MARKER_PREFIX) {
                return jpeg
            }
            val marker = byteAt(jpeg, offset + 1)
            if (marker == START_OF_SCAN) {
                // Compressed image data follows; copy it and everything after as is
                out.write(jpeg, offset, jpeg.size - offset)
                return out.toByteArray()
            }
            // The segment length counts its own two bytes but not the marker
            val length = (byteAt(jpeg, offset + 2) shl 8) or byteAt(jpeg, offset + 3)
            val end = offset + 2 + length
            if (length < 2 || end > jpeg.size) {
                return jpeg
            }
            if (!(marker == APP2 && hasIccProfileId(jpeg, offset + 4, end))) {
                out.write(jpeg, offset, end - offset)
            }
            offset = end
        }
    }

    private fun hasIccProfileId(jpeg: ByteArray, start: Int, end: Int): Boolean =
        end - start >= ICC_PROFILE_ID.size &&
            jpeg.copyOfRange(start, start + ICC_PROFILE_ID.size).contentEquals(ICC_PROFILE_ID)

    private fun byteAt(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF
}
