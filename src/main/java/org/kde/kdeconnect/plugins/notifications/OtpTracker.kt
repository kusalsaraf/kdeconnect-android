/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.notifications

/**
 * Decides which one-time codes from notifications should be sent to the other device's clipboard,
 * so that each code is sent at most once per notification.
 */
class OtpTracker {
    // Last code seen, per notification key
    private val codes = HashMap<String, String>()

    /**
     * Returns the code to send for this notification, or null if there is nothing new to send.
     *
     * Codes in notifications that were already showing ([isNewlyPosted] false) are remembered but
     * not returned, so a later update to such a notification doesn't resend an old code.
     */
    fun codeToSend(key: String, title: String?, text: String?, isNewlyPosted: Boolean): String? {
        val code = OtpExtractor.find(title, text) ?: return null
        val isNewCode = codes.put(key, code) != code
        return if (isNewlyPosted && isNewCode) code else null
    }

    fun forget(key: String) {
        codes.remove(key)
    }

    fun clear() {
        codes.clear()
    }
}
