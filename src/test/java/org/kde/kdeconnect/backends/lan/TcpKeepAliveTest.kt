/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.backends.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

class TcpKeepAliveTest {
    @Test
    fun setsKeepAliveAndTimings() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val socket = Socket()
        TcpKeepAlive.apply(socket) { _, option, value -> calls += option to value }
        assertTrue(socket.keepAlive)
        assertEquals(listOf(4 to 30, 5 to 10, 6 to 3), calls)
    }
}
