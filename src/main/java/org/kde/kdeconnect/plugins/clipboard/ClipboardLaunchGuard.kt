/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.os.SystemClock

/**
 * Limits launches of [ClipboardFloatingActivity] to one per copy: Android kills apps that start
 * activities in a tight loop (rapidActivityLaunch).
 */
class ClipboardLaunchGuard(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private var lastLaunch: Long? = null
    private var inFlight = false

    /** Returns true, and records the launch, if a new launch may start now. */
    @Synchronized
    fun tryStartLaunch(): Boolean {
        val now = clock()
        val last = lastLaunch
        if (last != null) {
            val elapsed = now - last
            // A launch that never reported back (the window never got focus) stops blocking after a while
            if (inFlight && elapsed < IN_FLIGHT_TIMEOUT_MS) {
                return false
            }
            if (elapsed < MIN_INTERVAL_MS) {
                return false
            }
        }
        lastLaunch = now
        inFlight = true
        return true
    }

    /** Called once the launched activity has read the clipboard or gone away. */
    @Synchronized
    fun launchFinished() {
        inFlight = false
    }

    companion object {
        const val MIN_INTERVAL_MS = 1_000L
        const val IN_FLIGHT_TIMEOUT_MS = 5_000L
    }
}
