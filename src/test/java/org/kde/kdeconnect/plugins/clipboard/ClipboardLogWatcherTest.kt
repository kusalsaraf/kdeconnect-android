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

class ClipboardLogWatcherTest {
    private val pkg = "org.kde.kdeconnect_tp.debug"
    private val denial = "11-20 09:18:42.655  3084  4424 E ClipboardService: op = 29 Denying clipboard access to $pkg, " +
        "application is not in focus nor is it a system service"
    private val allowed = "11-20 09:20:52.980  3084  1234 D ClipboardService: clipboardAccessAllowed: op=29 result=true callingPackage=$pkg"

    @Test
    fun denialForThisPackageMatches() {
        assertTrue(ClipboardLogWatcher.isDenialFor(denial, pkg))
    }

    @Test
    fun allowedLineIgnored() {
        assertFalse(ClipboardLogWatcher.isDenialFor(allowed, pkg))
    }

    @Test
    fun otherPackageIgnored() {
        assertFalse(ClipboardLogWatcher.isDenialFor(denial.replace(pkg, "com.example.app"), pkg))
    }

    @Test
    fun releaseIdDoesNotMatchDebugLine() {
        assertFalse(ClipboardLogWatcher.isDenialFor(denial, "org.kde.kdeconnect_tp"))
    }

    @Test
    fun debugIdDoesNotMatchReleaseLine() {
        assertFalse(ClipboardLogWatcher.isDenialFor(denial.replace(pkg, "org.kde.kdeconnect_tp"), pkg))
    }

    @Test
    fun denialAtEndOfLineMatches() {
        assertTrue(ClipboardLogWatcher.isDenialFor("E ClipboardService: Denying clipboard access to $pkg", pkg))
    }

    @Test
    fun denialFollowedByReadLinesLaunchesOnce() {
        var now = 0L
        var launches = 0
        val watcher = ClipboardLogWatcher(pkg, ClipboardLaunchGuard { now }) { launches++ }
        watcher.onLogLine(denial)
        repeat(10) {
            now += 100
            watcher.onLogLine(allowed) // what each successful read logs
        }
        assertEquals(1, launches)
    }
}
