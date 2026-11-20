/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect

enum class ForegroundNotificationAction { SendClipboard, SendFiles, RunCommand, Quit }

private const val MAX_NOTIFICATION_ACTIONS = 3

/**
 * The buttons on the persistent notification, in order. Quit is always present and last; Android shows at
 * most three actions, so Run command is dropped when there would be four.
 */
fun foregroundNotificationActions(
    connectedDevices: Int,
    canSyncAutomatically: Boolean,
    hasCommands: Boolean,
): List<ForegroundNotificationAction> {
    val actions = mutableListOf<ForegroundNotificationAction>()
    if (connectedDevices > 0 && !canSyncAutomatically) {
        actions += ForegroundNotificationAction.SendClipboard
    }
    if (connectedDevices == 1) {
        actions += ForegroundNotificationAction.SendFiles
        if (hasCommands) {
            actions += ForegroundNotificationAction.RunCommand
        }
    }
    actions += ForegroundNotificationAction.Quit
    if (actions.size > MAX_NOTIFICATION_ACTIONS) {
        actions -= ForegroundNotificationAction.RunCommand
    }
    return actions
}
