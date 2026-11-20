/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.backends.lan

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.IOException
import java.net.Socket

/**
 * TCP keepalive tuned so a link to a device that went away without closing it (a Mac going to sleep)
 * fails within about a minute instead of the OS default of about two hours. A device that is still
 * there answers the probes in its TCP stack, so healthy links are unaffected.
 */
object TcpKeepAlive {
    private const val TAG = "KDE/TcpKeepAlive"

    const val IDLE_SECONDS = 30
    const val INTERVAL_SECONDS = 10
    const val PROBE_COUNT = 3

    // Linux socket option numbers; Android doesn't expose them in OsConstants
    const val TCP_KEEPIDLE = 4
    const val TCP_KEEPINTVL = 5
    const val TCP_KEEPCNT = 6

    fun interface TcpOptionSetter {
        @Throws(IOException::class)
        fun setInt(socket: Socket, option: Int, value: Int)
    }

    private object OsTcpOptionSetter : TcpOptionSetter {
        override fun setInt(socket: Socket, option: Int, value: Int) {
            // fromSocket() duplicates the descriptor; options set on the duplicate apply to the same socket
            ParcelFileDescriptor.fromSocket(socket).use {
                try {
                    Os.setsockoptInt(it.fileDescriptor, OsConstants.IPPROTO_TCP, option, value)
                } catch (e: ErrnoException) {
                    throw IOException("setsockopt $option failed", e)
                }
            }
        }
    }

    fun apply(socket: Socket, setter: TcpOptionSetter = OsTcpOptionSetter) {
        socket.keepAlive = true
        try {
            setter.setInt(socket, TCP_KEEPIDLE, IDLE_SECONDS)
            setter.setInt(socket, TCP_KEEPINTVL, INTERVAL_SECONDS)
            setter.setInt(socket, TCP_KEEPCNT, PROBE_COUNT)
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't tune TCP keepalive, using the OS defaults", e)
        }
    }
}
