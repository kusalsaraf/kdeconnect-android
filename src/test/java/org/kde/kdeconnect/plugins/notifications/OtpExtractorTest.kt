/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.notifications

import org.junit.Assert
import org.junit.Test

class OtpExtractorTest {
    @Test
    fun codeBeforeKeyword() {
        Assert.assertEquals("482913", OtpExtractor.find("482913 is your OTP for login"))
    }

    @Test
    fun codeAfterKeyword() {
        Assert.assertEquals("7391", OtpExtractor.find("Your verification code is 7391."))
    }

    @Test
    fun letterPrefixAllowed() {
        Assert.assertEquals("123456", OtpExtractor.find("G-123456 is your Google verification code."))
    }

    @Test
    fun hyphenatedCodeIsJoined() {
        Assert.assertEquals("123456", OtpExtractor.find("Your WhatsApp code: 123-456\nDon't share this code"))
    }

    @Test
    fun keywordInAnyCase() {
        Assert.assertEquals("482913", OtpExtractor.find("VERIFICATION CODE\n482913"))
    }

    @Test
    fun bankSmsPicksOtp() {
        val sms = "Dear Customer, 482913 is your OTP for txn of Rs. 5,000.00 at AMAZON on card ending XX1234. " +
            "Valid till 23:30. Do not share OTP. -HDFC Bank"
        Assert.assertEquals("482913", OtpExtractor.find(sms))
    }

    @Test
    fun nearestCandidateWins() {
        Assert.assertEquals("4821", OtpExtractor.find("Order 55512 delivered. Your delivery code is 4821"))
    }

    @Test
    fun currencyAmountIgnored() {
        Assert.assertEquals("2468", OtpExtractor.find("Pay INR 4999 using OTP 2468"))
        Assert.assertEquals("2468", OtpExtractor.find("Pay ₹4999 using OTP 2468"))
    }

    @Test
    fun phoneNumbersAndDatesIgnored() {
        Assert.assertEquals("9081", OtpExtractor.find("Your OTP is 9081. Call 1800-123-4567 before 16/11/2026"))
    }

    @Test
    fun noKeywordGivesNull() {
        Assert.assertNull(OtpExtractor.find("Your order 482913 has shipped"))
    }

    @Test
    fun noCandidateGivesNull() {
        Assert.assertNull(OtpExtractor.find("Never share your OTP with anyone"))
    }

    @Test
    fun wrongLengthGivesNull() {
        Assert.assertNull(OtpExtractor.find("Your code is 123"))
        Assert.assertNull(OtpExtractor.find("Your code is 123456789"))
    }

    @Test
    fun keywordMustBeWholeWord() {
        Assert.assertNull(OtpExtractor.find("Decode 482913 later"))
        Assert.assertNull(OtpExtractor.find("Pinterest 4821 posts"))
    }
}
