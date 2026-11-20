/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.backends.lan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {
    private var now = 0L
    private val limiter = RateLimiter<String>(1000, 255) { now }

    @Test
    fun firstPasses() {
        assertFalse(limiter.shouldDiscard("a"))
    }

    @Test
    fun repeatWithinIntervalDiscarded() {
        limiter.shouldDiscard("a")
        now = 999
        assertTrue(limiter.shouldDiscard("a"))
    }

    @Test
    fun passesAfterInterval() {
        limiter.shouldDiscard("a")
        now = 1000
        assertFalse(limiter.shouldDiscard("a"))
    }

    @Test
    fun sameDeviceOtherAddressNotSuppressed() {
        val byDeviceAndAddress = RateLimiter<Pair<String, String>>(1000, 255) { 0L }
        byDeviceAndAddress.shouldDiscard("mac" to "172.30.200.53")
        assertFalse(byDeviceAndAddress.shouldDiscard("mac" to "192.168.1.3"))
    }
}
