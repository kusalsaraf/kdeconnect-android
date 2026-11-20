/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Log

class KdeConnectBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Log.e("KdeConnect", "Broadcast event: "+intent.getAction());

        val action = intent.action

        when (action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i("KdeConnect", "MyUpdateReceiver")
                // An update keeps whatever state KDE Connect was in
                if (RunState.isOn(context)) {
                    BackgroundService.Start(context)
                }
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                Log.i("KdeConnect", "KdeConnectBroadcastReceiver")
                // KDE Connect is always off after a phone restart, until the user opens it
                RunState.setOn(context, false)
            }

            WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION, WifiManager.WIFI_STATE_CHANGED_ACTION, ConnectivityManager.CONNECTIVITY_ACTION -> {
                Log.i("KdeConnect", "Connection state changed, trying to connect")
                BackgroundService.RefreshConnectionsIfOn(context)
            }

            Intent.ACTION_SCREEN_ON -> {
                BackgroundService.RefreshConnectionsIfOn(context)
            }

            else -> Log.i("BroadcastReceiver", "Ignoring broadcast event: ${intent.action}")
        }
    }
}
