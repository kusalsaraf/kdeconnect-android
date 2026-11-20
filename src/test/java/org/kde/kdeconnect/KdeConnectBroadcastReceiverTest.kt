/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect

import android.app.Application
import android.content.Intent
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class KdeConnectBroadcastReceiverTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    private fun receive(action: String) {
        KdeConnectBroadcastReceiver().onReceive(application, Intent(action))
    }

    private fun nextStartedService(): Intent? = shadowOf(application).nextStartedService

    @Test
    fun bootTurnsOffAndDoesNotStart() {
        RunState.setOn(application, true)
        receive(Intent.ACTION_BOOT_COMPLETED)
        assertFalse(RunState.isOn(application))
        assertNull(nextStartedService())
    }

    @Test
    fun packageReplacedWhileOnStarts() {
        RunState.setOn(application, true)
        receive(Intent.ACTION_MY_PACKAGE_REPLACED)
        assertEquals(BackgroundService::class.java.name, nextStartedService()?.component?.className)
    }

    @Test
    fun packageReplacedWhileOffDoesNotStart() {
        receive(Intent.ACTION_MY_PACKAGE_REPLACED)
        assertNull(nextStartedService())
    }

    @Test
    fun connectivityWhileOffDoesNotStart() {
        receive(WifiManager.WIFI_STATE_CHANGED_ACTION)
        receive(Intent.ACTION_SCREEN_ON)
        assertNull(nextStartedService())
        assertFalse(RunState.isOn(application))
    }

    @Test
    fun connectivityWhileOnStartsWithRefresh() {
        RunState.setOn(application, true)
        receive(WifiManager.WIFI_STATE_CHANGED_ACTION)
        val started = nextStartedService()
        assertEquals(BackgroundService::class.java.name, started?.component?.className)
        assertTrue(started!!.getBooleanExtra("refresh", false))
    }
}
