/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.provider.Settings
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
    private const val KEY_BOOT = "boot"
    private const val NO_BOOT_COUNT = -1

    /** On only if it was switched on during the current boot: a phone restart always leaves it off. */
    fun isOn(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return preferences.getBoolean(KEY_ON, false) && preferences.getInt(KEY_BOOT, NO_BOOT_COUNT - 1) == bootCount(context)
    }

    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ON, on)
            putInt(KEY_BOOT, bootCount(context))
        }
    }

    /**
     * The boot count already makes a restart switch KDE Connect off, so a late BOOT_COMPLETED can't switch off
     * a session the user started. Without a boot count (Android 6), the broadcast is what switches it off.
     */
    fun onBootCompleted(context: Context) {
        if (bootCount(context) == NO_BOOT_COUNT) {
            setOn(context, false)
        }
    }

    // BOOT_COUNT is an inlined string constant; before Android 7 the setting doesn't exist and the default is returned
    @SuppressLint("InlinedApi")
    private fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, NO_BOOT_COUNT)

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
