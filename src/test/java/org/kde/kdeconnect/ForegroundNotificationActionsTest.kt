/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kde.kdeconnect.ForegroundNotificationAction.Quit
import org.kde.kdeconnect.ForegroundNotificationAction.RunCommand
import org.kde.kdeconnect.ForegroundNotificationAction.SendClipboard
import org.kde.kdeconnect.ForegroundNotificationAction.SendFiles

class ForegroundNotificationActionsTest {
    @Test
    fun noDevicesOnlyQuit() {
        assertEquals(listOf(Quit), foregroundNotificationActions(0, false, false))
        assertEquals(listOf(Quit), foregroundNotificationActions(0, true, true))
    }

    @Test
    fun oneDeviceAutoSync() {
        assertEquals(listOf(SendFiles, RunCommand, Quit), foregroundNotificationActions(1, true, true))
    }

    @Test
    fun oneDeviceManualClipboardDropsRunCommand() {
        assertEquals(listOf(SendClipboard, SendFiles, Quit), foregroundNotificationActions(1, false, true))
    }

    @Test
    fun twoDevices() {
        assertEquals(listOf(SendClipboard, Quit), foregroundNotificationActions(2, false, true))
        assertEquals(listOf(Quit), foregroundNotificationActions(2, true, true))
    }

    @Test
    fun neverMoreThanThreeAndQuitLast() {
        for (devices in 0..3) {
            for (sync in listOf(true, false)) {
                for (commands in listOf(true, false)) {
                    val actions = foregroundNotificationActions(devices, sync, commands)
                    assertTrue(actions.size <= 3)
                    assertEquals(Quit, actions.last())
                }
            }
        }
    }
}
