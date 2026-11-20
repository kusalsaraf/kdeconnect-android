/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.backends.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kde.kdeconnect.MockSharedPreference
import java.net.InetAddress
import java.net.InetSocketAddress

class LastKnownAddressTest {
    private val settings = MockSharedPreference()

    @Test
    fun savesAndLoadsPrivateAddress() {
        LastKnownAddress.save(settings, InetAddress.getByName("192.168.1.3"), 1716)
        assertEquals(InetSocketAddress(InetAddress.getByName("192.168.1.3"), 1716), LastKnownAddress.load(settings))
    }

    @Test
    fun ignoresPublicAddress() {
        LastKnownAddress.save(settings, InetAddress.getByName("8.8.8.8"), 1716)
        assertNull(LastKnownAddress.load(settings))
    }

    @Test
    fun nothingSavedIsNull() {
        assertNull(LastKnownAddress.load(settings))
    }

    @Test
    fun portForOutgoingIsSocketPort() {
        assertEquals(1720, LastKnownAddress.rememberedPort(true, 1720, null))
    }

    @Test
    fun portForIncomingUsesIdentity() {
        assertEquals(1722, LastKnownAddress.rememberedPort(false, 51234, 1722))
    }

    @Test
    fun portForIncomingDefaults() {
        assertEquals(1716, LastKnownAddress.rememberedPort(false, 51234, null))
    }
}
