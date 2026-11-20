/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.content.Context
import android.os.Build
import android.util.Log
import org.kde.kdeconnect.helpers.ThreadHelper
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detects clipboard changes on Android 10+ by watching the system log for the line Android writes when it
 * denies KDE Connect background clipboard access, then opens [ClipboardFloatingActivity] to read the clipboard.
 * Needs READ_LOGS, granted over adb.
 */
class ClipboardLogWatcher(
    private val packageName: String,
    private val guard: ClipboardLaunchGuard,
    private val launch: () -> Unit,
) {
    fun onLogLine(line: String) {
        if (isDenialFor(line, packageName) && guard.tryStartLaunch()) {
            launch()
        }
    }

    companion object {
        private const val TAG = "KDE/ClipboardLogWatcher"
        private const val DENIAL = "Denying clipboard access to "

        /** Shared with [ClipboardFloatingActivity], which reports when a launch is done. */
        val launchGuard = ClipboardLaunchGuard()

        private var process: Process? = null

        /**
         * True if [line] is Android denying clipboard access to exactly [packageName]. Other lines that
         * mention the package, such as the one logged on every successful read, must not trigger a read.
         */
        fun isDenialFor(line: String, packageName: String): Boolean {
            val marker = DENIAL + packageName
            var index = line.indexOf(marker)
            while (index >= 0) {
                val end = index + marker.length
                if (end == line.length || line[end] == ',' || line[end].isWhitespace()) {
                    return true
                }
                index = line.indexOf(marker, index + 1)
            }
            return false
        }

        /** Starts watching the log, if automatic sync is possible and the watcher isn't already running. */
        @Synchronized
        fun ensureRunning(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !ClipboardPlugin.canSyncAutomatically(context) || process != null) {
                return
            }
            val appContext = context.applicationContext
            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            // Listen only to ClipboardService errors from now on
            val logcatFilter = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM) { "E ClipboardService" } else { "ClipboardService:E" }
            val newProcess = try {
                Runtime.getRuntime().exec(arrayOf("logcat", "-T", timeStamp, logcatFilter, "*:S"))
            } catch (e: IOException) {
                Log.e(TAG, "Couldn't start logcat", e)
                return
            }
            process = newProcess
            val watcher = ClipboardLogWatcher(appContext.packageName, launchGuard) {
                appContext.startActivity(ClipboardFloatingActivity.getIntent(appContext, false))
            }
            ThreadHelper.runOnNewThread {
                try {
                    newProcess.inputStream.bufferedReader().forEachLine(watcher::onLogLine)
                } catch (e: IOException) {
                    Log.i(TAG, "Log stream closed: ${e.message}")
                } finally {
                    onWatcherEnded(newProcess)
                }
            }
        }

        /** Stops watching the log. */
        @Synchronized
        fun stop() {
            process?.destroy()
            process = null
        }

        @Synchronized
        private fun onWatcherEnded(endedProcess: Process) {
            Log.i(TAG, "Clipboard log watcher stopped")
            if (process === endedProcess) {
                process = null
            }
        }
    }
}
