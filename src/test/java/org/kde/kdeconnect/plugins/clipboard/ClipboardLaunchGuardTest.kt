/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardLaunchGuardTest {
    private var now = 0L
    private val guard = ClipboardLaunchGuard { now }

    @Test
    fun firstLaunchAllowed() {
        assertTrue(guard.tryStartLaunch())
    }

    @Test
    fun secondWithinOneSecondIgnored() {
        guard.tryStartLaunch()
        guard.launchFinished()
        now = 999
        assertFalse(guard.tryStartLaunch())
    }

    @Test
    fun allowedAgainAfterOneSecond() {
        guard.tryStartLaunch()
        guard.launchFinished()
        now = 1000
        assertTrue(guard.tryStartLaunch())
    }

    @Test
    fun ignoredWhileInFlight() {
        guard.tryStartLaunch()
        now = 2000
        assertFalse(guard.tryStartLaunch())
    }

    @Test
    fun inFlightExpiresAfterFiveSeconds() {
        guard.tryStartLaunch()
        now = 5000
        assertTrue(guard.tryStartLaunch())
    }

    @Test
    fun burstGivesOneLaunch() {
        var launches = 0
        for (time in 0L..900L step 100) {
            now = time
            if (guard.tryStartLaunch()) {
                launches++
            }
        }
        assertEquals(1, launches)
    }
}
