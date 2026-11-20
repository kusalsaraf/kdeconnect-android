/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect

import android.app.Application
import android.app.Service
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect.backends.bluetooth.BluetoothLinkProvider
import org.kde.kdeconnect.backends.lan.LanLinkProvider
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
class BackgroundServiceTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        // Stand-ins for the real providers, which would open sockets and start discovery
        mockkConstructor(LanLinkProvider::class, BluetoothLinkProvider::class)
        every { anyConstructed<LanLinkProvider>().onStart() } just Runs
        every { anyConstructed<LanLinkProvider>().onStop() } just Runs
        every { anyConstructed<BluetoothLinkProvider>().onStart() } just Runs
        every { anyConstructed<BluetoothLinkProvider>().onStop() } just Runs
        // The Application under Robolectric isn't KdeConnect, so provide the instance the service asks for
        val kdeConnect = mockk<KdeConnect>(relaxed = true)
        every { kdeConnect.devices } returns ConcurrentHashMap()
        mockkObject(KdeConnect.Companion)
        every { KdeConnect.getInstance() } returns kdeConnect
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun startWhileOffStopsWithoutStartingDiscovery() {
        val controller = Robolectric.buildService(BackgroundService::class.java, Intent()).create()
        val result = controller.get().onStartCommand(Intent(), 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        verify(exactly = 0) { anyConstructed<LanLinkProvider>().onStart() }
    }

    @Test
    fun startWhileOnStartsDiscoveryOnce() {
        RunState.setOn(application, true)
        val controller = Robolectric.buildService(BackgroundService::class.java, Intent()).create()
        controller.get().onStartCommand(Intent(), 0, 1)
        controller.get().onStartCommand(Intent(), 0, 2)

        verify(exactly = 1) { anyConstructed<LanLinkProvider>().onStart() }
        controller.destroy()
    }
}
