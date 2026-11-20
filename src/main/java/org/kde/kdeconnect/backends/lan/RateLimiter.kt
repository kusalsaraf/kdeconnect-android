/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.backends.lan

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/** Discards a repeat of the same key that arrives within [intervalMs] of the last one let through. */
class RateLimiter<K : Any>(
    private val intervalMs: Long,
    private val maxEntries: Int,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lastAllowed = ConcurrentHashMap<K, Long>()

    fun shouldDiscard(key: K): Boolean {
        val now = clock()
        val last = lastAllowed[key]
        if (last != null && last + intervalMs > now) {
            return true
        }
        lastAllowed[key] = now
        if (lastAllowed.size > maxEntries) {
            lastAllowed.entries.removeIf { it.value + intervalMs < now }
        }
        return false
    }
}
