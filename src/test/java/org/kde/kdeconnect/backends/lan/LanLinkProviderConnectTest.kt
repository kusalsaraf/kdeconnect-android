/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.backends.lan

import org.junit.Assert.assertEquals
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

    @Test
    fun directConnectIdentityNeedsOnlyIdAndProtocolVersion() {
        // Stored device settings have no capabilities, so this can't go through DeviceInfo.toIdentityPacket()
        val packet = LanLinkProvider.directConnectIdentity("be461226c24e4f63a9b4275b3e05512f", 8)
        assertEquals("be461226c24e4f63a9b4275b3e05512f", packet.getString("deviceId"))
        assertEquals(8, packet.getInt("protocolVersion"))
        assertEquals("8", packet.getString("protocolVersion"))
    }

    @Test
    fun directConnectTargetsSkipsConnectedInFlightAndUnknown() {
        val address = InetSocketAddress("192.168.1.3", 1716)
        val known = mapOf("mac" to address, "pc" to address, "tablet" to address)
        val targets = LanLinkProvider.directConnectTargets(
            trustedDeviceIds = listOf("mac", "pc", "tablet", "phone2"),
            connectedDeviceIds = setOf("pc"),
            inFlight = setOf("tablet"),
            knownAddress = { known[it] },
        )
        assertEquals(mapOf("mac" to address), targets)
    }
}
