/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.backends.lan

import android.content.SharedPreferences
import org.kde.kdeconnect.helpers.isPrivateAddress
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException

/**
 * The address and TCP port a paired device was last connected on, kept in that device's settings, so the
 * phone can connect to it directly even when the device doesn't announce itself.
 */
object LastKnownAddress {
    const val KEY_ADDRESS = "lastLanAddress"
    const val KEY_PORT = "lastLanTcpPort"
    const val DEFAULT_TCP_PORT = 1716

    fun save(settings: SharedPreferences, address: InetAddress, port: Int) {
        if (!isPrivateAddress(address)) {
            return
        }
        settings.edit()
            .putString(KEY_ADDRESS, address.hostAddress)
            .putInt(KEY_PORT, port)
            .apply()
    }

    fun load(settings: SharedPreferences): InetSocketAddress? {
        val host = settings.getString(KEY_ADDRESS, null) ?: return null
        val port = settings.getInt(KEY_PORT, DEFAULT_TCP_PORT)
        return try {
            // The stored value is a numeric address, so this doesn't do a DNS lookup
            InetSocketAddress(InetAddress.getByName(host), port)
        } catch (_: UnknownHostException) {
            null
        }
    }

    /**
     * The port the device listens on. When we opened the connection it is the socket's remote port;
     * when the device connected to us, it is the one from its identity packet, or the default.
     */
    fun rememberedPort(outgoing: Boolean, socketPort: Int, identityTcpPort: Int?): Int =
        if (outgoing) socketPort else identityTcpPort ?: DEFAULT_TCP_PORT
}
