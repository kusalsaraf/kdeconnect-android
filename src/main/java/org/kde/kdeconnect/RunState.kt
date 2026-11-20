/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.core.content.getSystemService

/**
 * Whether the user has switched KDE Connect on.
 *
 * It is switched on when the user opens the app and off when they tap Quit, so automatic
 * triggers (boot, app updates, network changes) can tell "killed while on" from "quit by the user".
 */
object RunState {
    private const val TAG = "KDE/RunState"
    private const val PREFERENCES = "run_state"
    private const val KEY_ON = "on"

    fun isOn(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putBoolean(KEY_ON, on) }
    }

    /** A stop the user asked Android for (Force stop, Stop in the Active apps list) counts as Quit. */
    fun shouldTurnOffAfterExit(reason: Int): Boolean = reason == ApplicationExitInfo.REASON_USER_REQUESTED

    /** Switches off if the previous process was stopped at the user's request. */
    fun applyLastExitReason(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        val activityManager = context.getSystemService<ActivityManager>() ?: return
        val reason = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()?.reason ?: return
        if (shouldTurnOffAfterExit(reason) && isOn(context)) {
            Log.i(TAG, "Previous process was stopped by the user, switching off")
            setOn(context, false)
        }
    }
}
