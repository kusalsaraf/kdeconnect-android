/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.notifications

import org.junit.Assert
import org.junit.Test

class OtpTrackerTest {
    private val tracker = OtpTracker()

    @Test
    fun newlyPostedCodeIsSent() {
        Assert.assertEquals("482913", tracker.codeToSend("k", null, "482913 is your OTP", isNewlyPosted = true))
    }

    @Test
    fun sameCodeIsSentOnce() {
        tracker.codeToSend("k", null, "482913 is your OTP", isNewlyPosted = true)

        Assert.assertNull(tracker.codeToSend("k", null, "482913 is your OTP", isNewlyPosted = true))
    }

    @Test
    fun newCodeInSameNotificationIsSent() {
        tracker.codeToSend("k", null, "482913 is your OTP", isNewlyPosted = true)

        Assert.assertEquals("771234", tracker.codeToSend("k", null, "771234 is your OTP", isNewlyPosted = true))
    }

    @Test
    fun codeAlreadyShowingIsRememberedButNotSent() {
        Assert.assertNull(tracker.codeToSend("k", null, "482913 is your OTP", isNewlyPosted = false))

        // An update to that notification after a reconnect must not resend the old code
        Assert.assertNull(tracker.codeToSend("k", null, "482913 is your OTP", isNewlyPosted = true))
    }

    @Test
    fun notificationWithoutCodeGivesNull() {
        Assert.assertNull(tracker.codeToSend("k", "Mom", "Dinner at 8?", isNewlyPosted = true))
    }

    @Test
    fun forgottenNotificationCanSendAgain() {
        tracker.codeToSend("k", null, "482913 is your OTP", isNewlyPosted = true)

        tracker.forget("k")

        Assert.assertEquals("482913", tracker.codeToSend("k", null, "482913 is your OTP", isNewlyPosted = true))
    }

    @Test
    fun clearedTrackerCanSendAgain() {
        tracker.codeToSend("k", null, "482913 is your OTP", isNewlyPosted = true)

        tracker.clear()

        Assert.assertEquals("482913", tracker.codeToSend("k", null, "482913 is your OTP", isNewlyPosted = true))
    }
}
