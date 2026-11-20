/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.backends.lan

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
class LanLinkProviderConnectTest {
    @Test
    fun openSocketGivesUpWithinTimeout() {
        // Nothing answers on this address, like the Mac's unreachable internal address
        val start = System.nanoTime()
        assertThrows(IOException::class.java) {
            LanLinkProvider.openSocket(InetSocketAddress("10.255.255.1", 1716), timeoutMs = 500)
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $elapsedMs ms", elapsedMs < 3_000)
    }
}
