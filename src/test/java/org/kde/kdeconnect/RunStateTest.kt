/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect

import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RunStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun offByDefault() {
        assertFalse(RunState.isOn(context))
    }

    @Test
    fun onPersists() {
        RunState.setOn(context, true)
        assertTrue(RunState.isOn(context))
    }

    @Test
    fun userRequestedExitTurnsOff() {
        assertTrue(RunState.shouldTurnOffAfterExit(ApplicationExitInfo.REASON_USER_REQUESTED))
    }

    @Test
    fun otherExitsKeepState() {
        listOf(
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
        ).forEach { assertFalse(RunState.shouldTurnOffAfterExit(it)) }
    }

    @Test
    fun forceRefreshSwitchesOn() {
        BackgroundService.ForceRefreshConnections(context)
        assertTrue(RunState.isOn(context))
    }
}
